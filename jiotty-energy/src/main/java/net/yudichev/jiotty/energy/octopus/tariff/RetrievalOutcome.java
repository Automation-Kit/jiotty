package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.energy.Prices;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;

/// How one retrieval of a tariff's prices ended.
public sealed interface RetrievalOutcome {
    /// @return the instant from which the retrieval fetched prices
    Instant from();

    /// @param prices the prices that the retrieval returned
    /// @param from   the instant from which the retrieval fetched prices
    record Retrieved(Prices prices, Instant from) implements RetrievalOutcome {
        public Retrieved {
            checkNotNull(prices);
            checkNotNull(from);
        }
    }

    /// @param cause why the retrieval failed: Octopus refused or could not be reached, or returned rates that make no prices
    /// @param from  the instant from which the retrieval fetched prices
    record Failed(Throwable cause, Instant from) implements RetrievalOutcome {
        public Failed {
            checkNotNull(cause);
            checkNotNull(from);
        }
    }
}
