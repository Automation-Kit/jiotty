package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import org.jspecify.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class GraphRunner extends BaseIdempotentCloseable {
    private final Graph graph;
    private final SchedulingExecutor executor;

    protected GraphRunner(Graph graph, SchedulingExecutor executor) {
        this.graph = checkNotNull(graph);
        this.executor = checkNotNull(executor);
    }

    public Graph graph() {
        return graph;
    }

    public SchedulingExecutor executor() {
        return executor;
    }

    public abstract void scheduleNewWave(String triggeredBy);

    public final void panic(String reason) {
        panic(reason, null);
    }

    public abstract void panic(@Nullable String message, @Nullable Throwable cause);

    @Override
    protected void doClose() {
        graph().close();
    }
}
