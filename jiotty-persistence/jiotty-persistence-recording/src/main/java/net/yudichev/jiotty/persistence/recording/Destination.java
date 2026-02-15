package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.lang.Closeable;

public interface Destination extends Closeable {
    <R> Recorder<R> createRecorder(Config<R> destinationConfig);

    <R> Reader createReader(Config<R> destinationConfig);

    sealed interface Config<R> permits PostgresqlDestination.PsqlConfig, UIDestination.UIConfig {
        Class<R> recordType();

        DestinationType destinationType();
    }
}
