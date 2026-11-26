package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/// The prices of each tariff of the agreements in the forward window, as the price service of each priced tariff publishes those prices.
interface TariffPricesNode extends ServerNode {
    /// @return the [TariffPriceState] of each tariff of the agreements in the forward window; `null` until the resolutions of those tariffs are known
    @Nullable Map<String, TariffPriceState> priceStatesByTariffCode();
}
