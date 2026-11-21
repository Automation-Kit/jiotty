package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.security.OAuth2TokenManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface TeslaFleet {
    Closeable subscribeToTokenState(Consumer<AuthState> handler);

    /// See [OAuth2TokenManager#onNewAuthCode(String, String, Optional)]
    void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier);

    CompletableFuture<List<TeslaVehicleData>> listVehicles();

    CompletableFuture<TelemetryCreateConfigResponse> telemetryCreateConfig(TelemetryCreateConfigRequest request);

    TeslaVehicle vehicle(String vehicleVin);
}
