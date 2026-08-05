package net.yudichev.jiotty.energy.octopus;

/// Lazy registry of per-region price-forecast services keyed by region letter. The first [#forRegion] call for a given region creates and starts the
/// service and caches it; repeat calls for the same region return that instance.
public interface PriceForecastServiceRegistry {
    /// Returns the price-forecast service for `regionLetter`, creating and starting a new one on first call.
    ForecastEnergyPriceService forRegion(char regionLetter);
}
