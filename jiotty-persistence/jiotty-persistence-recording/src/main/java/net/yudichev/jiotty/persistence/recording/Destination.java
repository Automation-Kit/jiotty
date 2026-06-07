package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.Optional;

public interface Destination extends Closeable {
    <R> Recorder<R> createRecorder(Config<R> destinationConfig, Optional<String> userId);

    <R> Reader createReader(Config<R> destinationConfig, Optional<String> userId);

    <R> Deleter createDeleter(Config<R> destinationConfig, Optional<String> userId);

    sealed interface Config<R> permits PostgresqlDestination.PsqlConfig, UIDestination.UIConfig {
        Class<R> recordType();

        DestinationType destinationType();
    }
}
