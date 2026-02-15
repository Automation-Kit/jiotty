package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.time.DateTimeUtils;
import net.yudichev.jiotty.user.ui.StatusHistoryDisplayable;
import net.yudichev.jiotty.user.ui.UIServer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

final class UIDestinationImpl implements UIDestination {

    private final UIServer uiServer;
    private final DateTimeUtils.Formatter dateTimeFormatter;

    @Inject
    public UIDestinationImpl(UIServer uiServer, ZoneId zoneId) {
        this.uiServer = checkNotNull(uiServer);
        dateTimeFormatter = new DateTimeUtils.Formatter(zoneId);
    }

    @Override
    public <R> Recorder<R> createRecorder(Config<R> destinationConfig) {
        var config = (UIConfig<R>) destinationConfig;
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
        uiServer.registerDisplayable(displayable);
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
