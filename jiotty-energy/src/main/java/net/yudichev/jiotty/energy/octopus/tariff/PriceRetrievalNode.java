package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import org.jspecify.annotations.Nullable;

/// A node that retrieves a tariff's prices on its first wave, at each daily retrieval, and at each attempt that the plan of the latest outcome calls for.
interface PriceRetrievalNode extends ServerNode {
    /// @return the outcome of the latest retrieval that has ended; `null` until one has
    @Nullable RetrievalOutcome outcome();

    /// @return the plan that [#outcome()] calls for; `null` until a retrieval has ended
    @Nullable RetrievalPlan plan();
}
