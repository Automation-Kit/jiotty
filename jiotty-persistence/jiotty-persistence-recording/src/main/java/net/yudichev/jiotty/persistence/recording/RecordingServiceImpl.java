package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

final class RecordingServiceImpl extends BaseLifecycleComponent implements RecordingService {
    private static final Logger logger = LoggerFactory.getLogger(RecordingServiceImpl.class);

    private final Map<DestinationType, Destination> destinationsByType = new EnumMap<>(DestinationType.class);
    private final DestinationFactory destinationFactory;

    @Inject
    public RecordingServiceImpl(DestinationFactory destinationFactory) {
        this.destinationFactory = checkNotNull(destinationFactory);
    }

    @Override
    public <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig) {
        return whenStartedAndNotLifecycling(() -> getDestination(destinationConfig.destinationType()).createRecorder(destinationConfig));
    }

    @Override
    public Reader createReader(Destination.Config<?> destinationConfig) {
        return whenStartedAndNotLifecycling(() -> getDestination(destinationConfig.destinationType()).createReader(destinationConfig));
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, destinationsByType.values());
    }

    private Destination getDestination(DestinationType destinationConfig) {
        return destinationsByType.computeIfAbsent(destinationConfig, destinationFactory::create);
    }
}