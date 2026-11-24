package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/// The price-bearing view of an energy provider: the latest [PriceResult], a subscription to its changes, and when the provider expects those prices
/// to reach further ahead. A vendor's own provider interface extends this with the account-level concerns (auth state, account details).
public interface EnergyPriceService {
    /// The value that [#subscribeToNextRefreshTime] delivers when the provider expects no further prices: the profile ends where the account moves to a tariff
    /// that cannot be priced, or where no agreement follows. It is later than every profile end.
    Instant NO_REFRESH_EXPECTED = Instant.MAX;

    /// @return the latest result, or empty if none has been produced yet
    Optional<PriceResult> getPrices();

    /// Subscribes to results. The current result (if any) is delivered to the new subscriber immediately. The returned [Closeable] cancels the subscription.
    Closeable subscribeToPrices(Consumer<PriceResult> consumer);

    /// Subscribes to the instant at which this provider expects to hold prices reaching further ahead than the ones it holds now, so a consumer can tell a
    /// shortfall that is about to be filled from one that will stand. The current value (if any) is delivered immediately; nothing is delivered before the
    /// provider can answer.
    Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer);

    /// Subscribes to the account's [TariffTimeline]. The current value (if any) is delivered immediately; nothing is delivered until the tariff of every
    /// agreement in the timeline has been resolved. A timeline is delivered before any [Prices] built from it.
    Closeable subscribeToTariffTimeline(Consumer<TariffTimeline> consumer);

    /// A non-price outcome the provider can report instead of a price profile.
    sealed interface Failure extends PriceResult permits Failure.IncompatibleTariff, Failure.PriceRetrievalError {
        /// The active tariff is not one this service can price.
        ///
        /// @param activeTariffCode the tariff code the account is currently on
        record IncompatibleTariff(String activeTariffCode) implements Failure {}

        /// Fetching prices from the provider failed.
        ///
        /// @param cause the failure that ended the most recent retrieval attempt
        record PriceRetrievalError(Throwable cause) implements Failure {}
    }
}
