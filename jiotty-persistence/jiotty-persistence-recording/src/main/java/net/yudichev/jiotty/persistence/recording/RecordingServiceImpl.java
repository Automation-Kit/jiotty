package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

final class RecordingServiceImpl extends BaseLifecycleComponent implements RecordingService {
    private static final Logger logger = LogManager.getLogger(RecordingServiceImpl.class);

    private final Map<DestinationType, Destination> destinationsByType = new EnumMap<>(DestinationType.class);
    private final DestinationFactory destinationFactory;

    @Inject
    public RecordingServiceImpl(DestinationFactory destinationFactory) {
        this.destinationFactory = checkNotNull(destinationFactory);
    }

    @Override
    public <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig, Optional<String> userId) {
        return whenStartedAndNotLifecycling(() -> getDestination(destinationConfig.destinationType()).createRecorder(destinationConfig, userId));
    }

    @Override
    public Reader createReader(Destination.Config<?> destinationConfig, Optional<String> userId) {
        return whenStartedAndNotLifecycling(() -> getDestination(destinationConfig.destinationType()).createReader(destinationConfig, userId));
    }

    @Override
    public Deleter createDeleter(Destination.Config<?> destinationConfig, Optional<String> userId) {
        return whenStartedAndNotLifecycling(() -> getDestination(destinationConfig.destinationType()).createDeleter(destinationConfig, userId));
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, destinationsByType.values());
    }

    private Destination getDestination(DestinationType destinationConfig) {
        return destinationsByType.computeIfAbsent(destinationConfig, destinationFactory::create);
    }
}