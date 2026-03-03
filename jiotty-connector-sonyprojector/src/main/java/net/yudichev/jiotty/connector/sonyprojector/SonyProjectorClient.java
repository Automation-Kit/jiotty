package net.yudichev.jiotty.connector.sonyprojector;

import java.util.concurrent.CompletableFuture;

public interface SonyProjectorClient {
    CompletableFuture<Void> powerOn();

    CompletableFuture<Void> powerOff();

    CompletableFuture<SonyProjectorPowerState> getPowerState();
}
