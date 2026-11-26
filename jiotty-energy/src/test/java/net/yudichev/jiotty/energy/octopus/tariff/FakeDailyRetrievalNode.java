package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.BaseTestServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/// A [DailyRetrievalNode] whose value a test sets.
final class FakeDailyRetrievalNode extends BaseTestServerNode implements DailyRetrievalNode {
    private @Nullable Instant nextDailyRetrieval;

    FakeDailyRetrievalNode(GraphRunner graphRunner) {
        super(graphRunner);
    }

    @Override
    public @Nullable Instant nextDailyRetrieval() {
        return nextDailyRetrieval;
    }

    /// Sets the value and triggers the node in the next wave.
    void set(Instant value) {
        nextDailyRetrieval = value;
        triggerInNextWave();
    }
}
