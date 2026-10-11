package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface TeslaTelemetry {
    Closeable subscribeToMetrics(Consumer<? super TelemetryResult<TelemetryField>> listener);

    Closeable subscribeToConnectivity(Consumer<? super TelemetryResult<TelemetryConnectivityEvent>> listener);

    /// Removes the latest metric values and connectivity event kept for this vehicle, so a later subscriber receives nothing until the vehicle sends again.
    CompletableFuture<Void> clearRetained();
}
