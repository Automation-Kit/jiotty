package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Optional;

/// One retrieval of a [RetrievalPlan] to make.
@Value.Immutable
@PublicImmutablesStyle
interface BaseRetrievalAttempt {
    /// @return when to make the retrieval
    Instant at();

    /// @return the instant from which to fetch prices; empty to fetch them from the instant at which the retrieval is made
    Optional<Instant> from();
}
