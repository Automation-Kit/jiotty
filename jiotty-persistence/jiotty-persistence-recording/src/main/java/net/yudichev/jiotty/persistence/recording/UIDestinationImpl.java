package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.DateTimeUtils;
import net.yudichev.jiotty.user.ui.StatusHistoryDisplayable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

final class UIDestinationImpl implements UIDestination {
    private static final Logger logger = LogManager.getLogger(UIDestinationImpl.class);

    @Override
    public <R> Recorder<R> createRecorder(Config<R> destinationConfig) {
        var config = (UIConfig<R>) destinationConfig;
        var dateTimeFormatter = new DateTimeUtils.Formatter(config.zoneId());
        var renderer = config.renderer().get();
        renderer.initialise(dateTimeFormatter);
        var displayable = new StatusHistoryDisplayable<String, R>(
                config.title(),
                config.windowSize(),
                Function.identity(),
                status -> dateTimeFormatter.toFullDateAndTimeMins(status.lastChanged()),
                (status, appender) -> renderer.render(status.status(), appender),
                config.downloadHandler(),
                config.textFormat());
        var displayableRegistration = config.uiServer().registerDisplayable(displayable);
        return new Recorder<>() {
            private R lastRecorded;

            @Override
            public void record(Instant timestamp, R recordable) {
                if (!Objects.equals(lastRecorded, recordable)) {
                    displayable.addEvent(config.displayableEventKeyExtractor().apply(recordable), recordable, timestamp);
                    lastRecorded = recordable;
                }
            }

            @Override
            public void record(DestinationType destinationType, Instant timestamp, R recordable) {
                if (destinationType == config.destinationType()) {
                    record(timestamp, recordable);
                }
            }

            @Override
            public void close() {
                Closeable.closeSafelyIfNotNull(logger, displayableRegistration);
            }
        };
    }

    @Override
    public <R> Reader createReader(Config<R> destinationConfig) {
        throw new UnsupportedOperationException("createReader");
    }

    @Override
    public void close() {
    }
}
