package net.yudichev.jiotty.connector.tesla.fleet;

import java.util.concurrent.CompletableFuture;

/// Partner-level Tesla Fleet API: operations scoped to the registered third-party application rather than to any user or
/// vehicle. They require no user authorisation, so a single instance serves the whole deployment.
public interface TeslaFleetPartner {
    CompletableFuture<PartnerAccount> registerPartnerDomain(String domain);

    CompletableFuture<PartnerPublicKey> getPartnerPublicKey(String domain);

    /// Removes this application's telemetry configuration from the vehicle, whichever user set it up, so the vehicle stops streaming to this application.
    CompletableFuture<TelemetryDeleteConfigResponse> deleteTelemetryConfig(String vin);
}
