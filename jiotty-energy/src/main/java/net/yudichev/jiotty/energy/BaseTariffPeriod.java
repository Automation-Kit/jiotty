package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Optional;

/// One tariff agreement of the account over the half-open window `[from, to)`.
@Value.Immutable
@PublicImmutablesStyle
interface BaseTariffPeriod {
    Instant from();

    /// Empty while the supplier has set no end date.
    Optional<Instant> to();

    TariffPricing pricing();

    /// Whether the supplier controls the charging of the customer's car on this tariff, as on Intelligent Octopus Go.
    boolean supplierManagedCharging();
}
