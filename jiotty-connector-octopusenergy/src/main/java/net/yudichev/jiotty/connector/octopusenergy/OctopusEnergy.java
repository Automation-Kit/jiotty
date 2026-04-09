package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface OctopusEnergy {
    Closeable subscribeToApiKeyState(Consumer<AuthState> handler);

    CompletableFuture<List<StandardUnitRate>> getAgilePrices(Instant periodFrom, Instant periodTo);
}
