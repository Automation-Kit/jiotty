package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;

public interface Recorder<R> extends Closeable {
    void record(Instant timestamp, R recordable);

    void record(DestinationType destinationType, Instant timestamp, R recordable);

    @Override
    default void close() {
    }
}
