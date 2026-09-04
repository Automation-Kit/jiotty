package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/// The price-bearing view of an energy provider: the latest price-or-failure result, a subscription to its changes, and when the provider expects those prices
/// to reach further ahead. A vendor's own provider interface extends this with the account-level concerns (auth state, account details).
public interface EnergyPriceService {
    /// @return the latest price-or-failure result, or empty if none has been produced yet
    Optional<Either<Prices, Failure>> getPrices();

    /// Subscribes to price-or-failure results. The current result (if any) is delivered to the new subscriber immediately. The returned [Closeable] cancels
    /// the subscription.
    Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer);

    /// Subscribes to the instant at which this provider expects to hold prices reaching further ahead than the ones it holds now, so a consumer can tell a
    /// shortfall that is about to be filled from one that will stand. The current value (if any) is delivered immediately; nothing is delivered before the
    /// provider can answer.
    Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer);

    /// A non-price outcome the provider can report instead of a price profile.
    sealed interface Failure permits Failure.IncompatibleTariff, Failure.PriceRetrievalError {
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
