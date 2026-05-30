package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;

import java.util.Optional;
import java.util.function.Consumer;

/// The price-bearing view of an energy provider: the latest price-or-failure result and a subscription to its changes. [EnergyProviderService] extends this
/// with the account-level concerns (auth state, account details).
public interface EnergyPriceService {
    /// @return the latest price-or-failure result, or empty if none has been produced yet
    Optional<Either<Prices, Failure>> getPrices();

    /// Subscribes to price-or-failure results. The current result (if any) is delivered to the new subscriber immediately. The returned [Closeable] cancels
    /// the subscription.
    Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer);

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
