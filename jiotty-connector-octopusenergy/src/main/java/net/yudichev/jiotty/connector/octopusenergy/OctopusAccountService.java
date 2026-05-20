package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Per-account handle on Octopus data that is specific to one user's account — meter points, consumption. Obtain via [OctopusEnergy#account]. Callers own this
/// handle's lifecycle; closing releases the underlying resources held on their behalf and a subsequent [OctopusEnergy#account] call with the same credentials
/// returns a fresh handle.
public interface OctopusAccountService extends Closeable {
    /// Subscribes to the auth-state observable for the credentials this handle is bound to. The pre-fetch state is [AuthState.TransientFailure]
    /// (`"Initialising"`); a successful first fetch transitions to [AuthState.Success]; an authentication rejection transitions to
    /// [AuthState.PermanentFailure]; other transport failures keep the state transient. The returned [Closeable] cancels the subscription.
    Closeable subscribeToAuthState(Consumer<AuthState> handler);

    /// Returns a future of the account payload. The same future is returned across calls for the lifetime of this handle; if the underlying fetch fails, the
    /// future fails with the same cause.
    CompletableFuture<OctopusAccountData> getAccount();

    /// Returns one [MpanAndMeter] row per meter at each meter point on this account. An account with no meter points yields an empty list — that case is not an
    /// error. The future fails only if the underlying account fetch failed.
    CompletableFuture<List<MpanAndMeter>> getMpanAndMeter();

    /// Returns half-hourly consumption rows for the given `(mpan, meterSerial)` over `[from, to]`. Auth uses this handle's stored credentials.
    CompletableFuture<List<ConsumptionRow>> getConsumption(String mpan, String meterSerial, Instant from, Instant to);
}
