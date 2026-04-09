package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.security.OAuth2TokenManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface TeslaFleet {
    Closeable subscribeToTokenState(Consumer<AuthState> handler);

    /// See [OAuth2TokenManager#onNewAuthCode]
    void onNewAuthCode(String authCode, String redirectUri);

    CompletableFuture<List<TeslaVehicleData>> listVehicles();

    CompletableFuture<PartnerAccount> registerPartnerDomain(String domain);

    CompletableFuture<PartnerPublicKey> getPartnerPublicKey(String domain);

    CompletableFuture<TelemetryCreateConfigResponse> telemetryCreateConfig(TelemetryCreateConfigRequest request);

    TeslaVehicle vehicle(String vehicleVin);
}
