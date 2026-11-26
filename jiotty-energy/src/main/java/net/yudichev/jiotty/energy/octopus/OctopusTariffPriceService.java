package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.energy.PriceResult;

import java.time.Instant;
import java.util.function.Consumer;

/// The prices of a single Octopus tariff. Each instance is bound to one `(productCode, tariffCode)` and serves the prices of that tariff to every subscriber,
/// whichever account holds it and for whatever part of time.
public interface OctopusTariffPriceService extends LifecycleComponent {
    /// Subscribes to results. The current result (if any) is delivered to the new subscriber immediately. The returned [Closeable] cancels the subscription.
    Closeable subscribeToPrices(Consumer<PriceResult> consumer);

    /// Subscribes to the instant at which the published prices are next due to reach further ahead than the current ones. The current value (if any) is
    /// delivered immediately; nothing is delivered until the first retrieval has completed or failed.
    Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer);
}
