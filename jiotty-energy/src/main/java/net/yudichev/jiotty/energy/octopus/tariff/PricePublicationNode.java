package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.PriceResult;
import org.jspecify.annotations.Nullable;

/// A node holding the result of a tariff's retrievals that subscribers are to hold: the prices of each successful retrieval, or the failure of a failed one.
/// A failure is published at once if [TariffRetrieval#publishesFailureAtOnce(boolean)] returns `true`, and otherwise at the next daily retrieval if no
/// prices have arrived by then.
interface PricePublicationNode extends ServerNode {
    /// @return the result that subscribers are to hold; `null` until there is one
    @Nullable PriceResult publishedResult();
}
