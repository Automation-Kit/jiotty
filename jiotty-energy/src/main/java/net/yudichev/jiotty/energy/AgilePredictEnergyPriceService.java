package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.inject.LifecycleComponent;

/// An [EnergyPriceService] for AgilePredict forecasts of one Octopus region. Each instance is bound to one region letter and serves the forecast values
/// published for that region to every subscriber.
public interface AgilePredictEnergyPriceService extends EnergyPriceService, LifecycleComponent {
}
