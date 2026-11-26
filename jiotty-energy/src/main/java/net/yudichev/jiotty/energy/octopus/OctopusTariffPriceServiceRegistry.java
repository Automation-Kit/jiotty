package net.yudichev.jiotty.energy.octopus;

import java.util.concurrent.CompletableFuture;

/// A registry of the [OctopusTariffPriceService] of each Octopus tariff, one per tariff code, shared by every account on that tariff.
public interface OctopusTariffPriceServiceRegistry {
    /// Returns where the prices of `tariffCode` come from, starting its price service on the first call. The returned future fails when the source cannot
    /// be determined, as when `tariffCode` names no known region, and the next call then tries again. The returned future never completes if the registry
    /// stops before the source is determined.
    CompletableFuture<TariffPriceSource> forTariff(String tariffCode);
}
