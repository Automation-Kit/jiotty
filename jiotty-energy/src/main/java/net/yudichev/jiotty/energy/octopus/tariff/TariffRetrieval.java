package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.energy.Prices;

import java.time.Instant;
import java.time.LocalTime;
import java.util.concurrent.CompletableFuture;

/// How the prices of one kind of tariff are retrieved: which prices a retrieval fetches from Octopus, and what follows each retrieval.
interface TariffRetrieval {
    /// @return the UK clock time of the daily retrieval
    LocalTime dailyRetrievalTime();

    /// Retrieves the prices from `from`.
    ///
    /// @implSpec An implementation must report each failure by completing the returned future exceptionally, and must not throw it. This covers an Octopus
    ///           call that fails and rates from which no prices can be made.
    CompletableFuture<Prices> fetch(Instant from);

    /// @return the retrieval that `outcome` calls for before the next daily one, and the next refresh time that `outcome` implies
    RetrievalPlan plan(RetrievalOutcome outcome, Instant now);

    /// @param pricesPublished whether the published result is prices
    /// @return whether a failed retrieval is published at once, rather than at the next daily retrieval if the failures persist
    boolean publishesFailureAtOnce(boolean pricesPublished);
}
