package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Optional;

/// What follows a [RetrievalOutcome].
@Value.Immutable
@PublicImmutablesStyle
interface BaseRetrievalPlan {
    /// @return the retrieval to make before the next daily one; empty when there is none
    Optional<RetrievalAttempt> attempt();

    /// @return when the published prices are next expected to reach further than they do now
    Instant nextRefreshTime();
}
