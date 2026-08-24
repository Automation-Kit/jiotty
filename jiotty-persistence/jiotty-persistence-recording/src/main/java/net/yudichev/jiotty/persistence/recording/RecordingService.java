package net.yudichev.jiotty.persistence.recording;

import java.util.Optional;
import java.util.Set;

public interface RecordingService {
    <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig, Optional<String> userId);

    default <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig) {
        return createRecorder(destinationConfig, Optional.empty());
    }

    /// Creates one recorder fanning out to every destination in `destinationConfigs`, so one destination failing does not stop the others.
    <R> Recorder<R> createRecorder(Set<Destination.Config<R>> destinationConfigs, Optional<String> userId);

    default <R> Recorder<R> createRecorder(Set<Destination.Config<R>> destinationConfigs) {
        return createRecorder(destinationConfigs, Optional.empty());
    }

    Reader createReader(Destination.Config<?> destinationConfig, Optional<String> userId);

    default Reader createReader(Destination.Config<?> destinationConfig) {
        return createReader(destinationConfig, Optional.empty());
    }

    Deleter createDeleter(Destination.Config<?> destinationConfig, Optional<String> userId);

    default Deleter createDeleter(Destination.Config<?> destinationConfig) {
        return createDeleter(destinationConfig, Optional.empty());
    }
}
