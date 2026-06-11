package net.yudichev.jiotty.energy;

/// Lazy registry of per-region AgilePredict forecast services keyed by region letter. The first [#forRegion] call for a given region creates and starts the
/// service and caches it; repeat calls for the same region return that instance.
public interface AgilePredictPriceServiceRegistry {
    /// Returns the AgilePredict price service for `regionLetter`, creating and starting a new one on first call.
    EnergyPriceService forRegion(char regionLetter);
}
