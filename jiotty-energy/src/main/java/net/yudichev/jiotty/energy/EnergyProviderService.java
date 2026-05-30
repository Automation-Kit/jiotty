package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;

import java.util.function.Consumer;

/// A user's energy provider: everything [EnergyPriceService] offers, plus the account-level concerns bound to the same credentials — authentication state and
/// the account's metering/tariff details. One instance represents one account with one provider.
public interface EnergyProviderService extends EnergyPriceService {
    /// Subscribes to authentication-state changes for the account's credentials. The current state is delivered to the new subscriber immediately. The
    /// returned [Closeable] cancels the subscription.
    Closeable subscribeToAuthState(Consumer<AuthState> consumer);

    /// Subscribes to the account's details ([AccountFetchResult]). The latest result is delivered to the new subscriber immediately; before any result is
    /// available, nothing is delivered. The returned [Closeable] cancels the subscription.
    Closeable subscribeToAccountDetails(Consumer<AccountFetchResult> consumer);
}
