package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/// The source of the prices of each tariff of the agreements in the forward window, as [OctopusTariffPriceServiceRegistry#forTariff] resolves it.
interface TariffSourcesNode extends ServerNode {
    /// @return the resolution of the tariff of each agreement in the forward window; `null` until the agreements in the forward window are known
    @Nullable Map<String, TariffResolution> resolutionsByTariffCode();
}
