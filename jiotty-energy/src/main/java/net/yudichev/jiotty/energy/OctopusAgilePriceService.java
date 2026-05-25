package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.inject.LifecycleComponent;

/// An [EnergyPriceService] for a single Octopus Agile tariff. Each instance is bound to one `(productCode, tariffCode)` and serves the prices published under
/// that tariff to every subscriber.
public interface OctopusAgilePriceService extends EnergyPriceService, LifecycleComponent {
}
