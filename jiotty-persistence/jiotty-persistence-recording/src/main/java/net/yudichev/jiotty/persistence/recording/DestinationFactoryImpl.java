package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;

import static com.google.common.base.Preconditions.checkNotNull;

final class DestinationFactoryImpl implements DestinationFactory {
    private final PostgresqlDestinationFactory postgresqlDestinationFactory;
    private final UIDestinationFactory uiDestinationFactory;

    @Inject
    public DestinationFactoryImpl(PostgresqlDestinationFactory postgresqlDestinationFactory,
                                  UIDestinationFactory uiDestinationFactory) {
        this.postgresqlDestinationFactory = checkNotNull(postgresqlDestinationFactory);
        this.uiDestinationFactory = checkNotNull(uiDestinationFactory);
    }

    @Override
    public Destination create(DestinationType type) {
        return switch (type) {
            case POSTGRESQL -> {
                var destination = postgresqlDestinationFactory.create();
                destination.initialise();
                yield destination;
            }
            case UI -> uiDestinationFactory.create();
        };
    }
}
