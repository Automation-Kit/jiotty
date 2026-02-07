package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;

public interface TeslaTelemetry {
    Closeable subscribeToMetrics(Consumer<? super TelemetryField> listener);

    Closeable subscribeToConnectivity(Consumer<? super TelemetryConnectivityEvent> listener);

    Closeable subscribeToBrokerConnectionStatus(Consumer<BrokerConnectionStatus> listener);

    enum BrokerConnectionStatus {CONNECTED, DISCONNECTED}
}
