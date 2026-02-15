package net.yudichev.jiotty.persistence.recording;

interface DestinationFactory {
    Destination create(DestinationType type);
}
