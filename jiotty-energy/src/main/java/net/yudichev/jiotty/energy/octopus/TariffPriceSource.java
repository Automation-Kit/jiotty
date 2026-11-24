package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.energy.TariffPricing;

import static com.google.common.base.Preconditions.checkNotNull;

/// Where the prices of one Octopus tariff come from.
sealed interface TariffPriceSource {
    /// A tariff whose prices can be retrieved.
    ///
    /// @param service                 the service that publishes the tariff's prices
    /// @param pricing                 [TariffPricing#DYNAMIC] or [TariffPricing#PUBLISHED]
    /// @param supplierManagedCharging whether Octopus manages the charging of the customer's car on this tariff
    record Priced(OctopusTariffPriceService service, TariffPricing pricing, boolean supplierManagedCharging) implements TariffPriceSource {
        public Priced {
            checkNotNull(service);
            checkNotNull(pricing);
            assert switch (pricing) {
                case DYNAMIC, PUBLISHED -> true;
                case UNSUPPORTED -> false;
            } : "a priced tariff has pricing " + pricing;
        }
    }

    /// No prices can be retrieved for the tariff.
    final class Unsupported implements TariffPriceSource {
        public static final Unsupported INSTANCE = new Unsupported();

        private Unsupported() {
        }
    }
}
