package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.function.Predicate;

/// The account's tariff agreements that overlap the period from now over which the [EnergyPriceService] publishing this timeline fetches prices. The
/// prices may stop before the last of these agreements ends.
///
/// @param periods the agreements, in order of their start; empty when none falls in that period
public record TariffTimeline(List<TariffPeriod> periods) {
    public TariffTimeline {
        periods = ImmutableList.copyOf(periods);
    }

    /// @return whether any of [#periods] has [TariffPricing#DYNAMIC] pricing
    public boolean hasDynamicPricing() {
        return anyPeriod(period -> period.pricing().isDynamic());
    }

    /// @return whether any of [#periods] has [TariffPeriod#supplierManagedCharging()]
    public boolean hasSupplierManagedCharging() {
        return anyPeriod(TariffPeriod::supplierManagedCharging);
    }

    private boolean anyPeriod(Predicate<TariffPeriod> predicate) {
        for (TariffPeriod period : periods) {
            if (predicate.test(period)) {
                return true;
            }
        }
        return false;
    }
}
