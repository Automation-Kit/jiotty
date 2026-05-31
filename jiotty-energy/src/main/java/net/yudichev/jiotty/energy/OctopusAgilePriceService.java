package net.yudichev.jiotty.energy;

import com.google.inject.assistedinject.Assisted;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;

/// An [EnergyPriceService] for a single Octopus Agile tariff. Each instance is bound to one `(productCode, tariffCode)` and serves the prices published under
/// that tariff to every subscriber.
public interface OctopusAgilePriceService extends EnergyPriceService, LifecycleComponent {
    interface Factory {
        OctopusAgilePriceService create(OctopusRegionService regionService,
                                        @Assisted("productCode") String productCode,
                                        @Assisted("tariffCode") String tariffCode);
    }
}
