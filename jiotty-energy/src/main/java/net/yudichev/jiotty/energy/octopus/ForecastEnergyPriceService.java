package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.energy.Prices;

import java.util.function.Consumer;

/// Serves price forecasts for one Octopus region. Each instance is bound to one region letter and publishes that region's forecast profiles to every
/// subscriber. Retrieval failures are reported to the operator rather than to subscribers, so the profile a subscriber last received stands until a fresh
/// one replaces it.
public interface ForecastEnergyPriceService {
    /// Subscribes to forecast price profiles. The latest profile (if any) is delivered to the new subscriber immediately. The returned [Closeable] cancels
    /// the subscription.
    Closeable subscribeToPrices(Consumer<Prices> consumer);
}
