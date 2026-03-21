package net.yudichev.jiotty.persistence.recording;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

public final class CompositeRecorder<R> implements Recorder<R> {
    private static final Logger logger = LogManager.getLogger(CompositeRecorder.class);

    private final List<Recorder<R>> recorders;

    @SafeVarargs
    public CompositeRecorder(Recorder<R>... recorders) {
        this(List.of(recorders));
    }

    public CompositeRecorder(List<Recorder<R>> recorders) {
        this.recorders = checkNotNull(recorders);
    }

    @Override
    public void record(Instant timestamp, R recordable) {
        forEachRecorder(recordable, recorder -> recorder.record(timestamp, recordable));
    }

    @Override
    public void record(DestinationType destinationType, Instant timestamp, R recordable) {
        forEachRecorder(recordable, recorder -> recorder.record(destinationType, timestamp, recordable));
    }

    private void forEachRecorder(R recordable, Consumer<Recorder<R>> consumer) {
        for (Recorder<R> recorder : recorders) {
            try {
                consumer.accept(recorder);
            } catch (RuntimeException e) {
                logger.warn("Failed recording {} to {}", recordable, recorder, e);
            }
        }
    }
}
