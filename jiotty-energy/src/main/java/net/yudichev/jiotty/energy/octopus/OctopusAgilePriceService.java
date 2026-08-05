package net.yudichev.jiotty.energy.octopus;

import com.google.inject.assistedinject.Assisted;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.energy.EnergyPriceService;

/// An [EnergyPriceService] for a single Octopus Agile tariff. Each instance is bound to one `(productCode, tariffCode)` and serves the prices published under
/// that tariff to every subscriber.
public interface OctopusAgilePriceService extends EnergyPriceService, LifecycleComponent {
    interface Factory {
        OctopusAgilePriceService create(OctopusRegionService regionService,
                                        @Assisted("productCode") String productCode,
                                        @Assisted("tariffCode") String tariffCode);
    }
}
