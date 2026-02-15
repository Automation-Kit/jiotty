package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.persistence.psql.PsqlDataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static net.yudichev.jiotty.persistence.recording.RecordingModule.Dependency;
import static net.yudichev.jiotty.persistence.recording.RecordingModule.PsqlExecutor;

final class ReadOnlyPostgresqlDestination extends PostgresqlDestinationImpl {
    private static final Logger logger = LoggerFactory.getLogger(ReadOnlyPostgresqlDestination.class);

    @Inject
    public ReadOnlyPostgresqlDestination(@PsqlExecutor Provider<SchedulingExecutor> executorProvider, @Dependency PsqlDataSourceFactory dataSourceFactory) {
        super(executorProvider, dataSourceFactory);
    }

    @Override
    public <R> Recorder<R> createRecorder(Destination.Config<R> destinationConfig) {
        return new Recorder<>() {
            @Override
            public void record(Instant timestamp, R recordable) {
                logger.info("Dummy-recorded to PSQL: {}, {}", timestamp, recordable);
            }

            @Override
            public void record(DestinationType destinationType, Instant timestamp, R recordable) {
                if (destinationType == destinationConfig.destinationType()) {
                    record(timestamp, recordable);
                }
            }
        };
    }
}
