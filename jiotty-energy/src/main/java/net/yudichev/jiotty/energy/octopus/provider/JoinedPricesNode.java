package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.PriceResult;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/// The prices of the agreements in the forward window, joined into one profile across each switch from one agreement to the next, or a failure when those
/// prices are unavailable.
interface JoinedPricesNode extends ServerNode {
    /// @return one of:
    /// - the failure of the account fetch, while no fetch has succeeded;
    /// - otherwise, the joined prices, once the [TariffTimelineNode]'s timeline is known;
    /// - or the failure of the price service of the tariff in force.
    ///
    /// `null` until one of these is available
    @Nullable PriceResult result();

    /// @return when the joined prices are next expected to reach further than they do now; `null` until the price service of a priced tariff in the forward
    ///         window has published when its prices are next refreshed
    @Nullable Instant nextRefreshTime();
}
