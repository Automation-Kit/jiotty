package net.yudichev.jiotty.persistence.recording;

import java.util.Set;

public interface RecordingService {
    <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig);

    default <R> Recorder<R> createRecorder(Set<Destination.Config<R>> destinationConfigs) {
        return new CompositeRecorder<>(destinationConfigs.stream().map(this::createRecorder).toList());
    }

    Reader createReader(Destination.Config<?> destinationConfig);
}
