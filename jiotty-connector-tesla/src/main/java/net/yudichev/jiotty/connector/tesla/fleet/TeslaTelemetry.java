package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;

public interface TeslaTelemetry {
    Closeable subscribeToMetrics(Consumer<? super TelemetryResult<TelemetryField>> listener);

    Closeable subscribeToConnectivity(Consumer<? super TelemetryResult<TelemetryConnectivityEvent>> listener);

    enum BrokerConnectionStatus {CONNECTED, DISCONNECTED}
}
