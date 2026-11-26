package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.ServerNode;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/// When a tariff's prices are retrieved each day: [#nextDailyRetrieval()] changes as each daily retrieval falls due.
interface DailyRetrievalNode extends ServerNode {
    /// @return the first instant after the wave time at which UK clocks show [TariffRetrieval#dailyRetrievalTime()]; `null` before the first wave
    @Nullable Instant nextDailyRetrieval();
}
