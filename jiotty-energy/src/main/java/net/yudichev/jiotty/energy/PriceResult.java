package net.yudichev.jiotty.energy;

/// What an [EnergyPriceService] reports: a [Prices] profile, or the [EnergyPriceService.Failure] that stands in its place.
public sealed interface PriceResult permits Prices, EnergyPriceService.Failure {
}
