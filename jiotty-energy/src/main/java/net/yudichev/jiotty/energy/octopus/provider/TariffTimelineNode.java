package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import net.yudichev.jiotty.energy.TariffTimeline;
import org.jspecify.annotations.Nullable;

/// The agreements in the forward window and how each is priced.
interface TariffTimelineNode extends ServerNode {
    /// @return one period per agreement in the forward window, in the order of their start, as of the latest wave in which every agreement's tariff was
    ///         resolved; `null` until such a wave
    @Nullable TariffTimeline timeline();
}
