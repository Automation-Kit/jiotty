package net.yudichev.jiotty.common.graph.server;

import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.async.QueueFullException;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

/// Runs one graph on its executor: it queues at most one wave task at a time, and panics the graph when the executor rejects that task.
public abstract class GraphRunner extends BaseIdempotentCloseable {
    @VisibleForTesting
    static final String WAVE_REJECTED_PANIC_REASON = "Could not queue a wave";
    private static final Logger logger = LogManager.getLogger(GraphRunner.class);

    private final Graph graph;
    private final SchedulingExecutor executor;
    /// Whether a wave task is queued. Confined to the graph's thread, where both [#scheduleNewWave] and the wave task run.
    private boolean waveScheduled;

    protected GraphRunner(Graph graph, SchedulingExecutor executor) {
        this.graph = checkNotNull(graph);
        this.executor = checkNotNull(executor);
    }

    @Override
    protected void doClose() {
        graph().close();
    }

    public Graph graph() {
        return graph;
    }

    public SchedulingExecutor executor() {
        return executor;
    }

    /// Queues a wave task unless the graph is in a wave, closed, or already has one queued; call it on the graph's thread. Panics the graph if the executor
    /// rejects the task.
    public final void scheduleNewWave(String triggeredBy) {
        if (graph.inWave() || graph.isClosedPlain() || waveScheduled) {
            logger.debug("Not scheduling a wave for '{}': in a wave, closed or already scheduled", triggeredBy);
            return;
        }
        // Set before queuing, since an executor that runs the task inline clears it before execute returns.
        waveScheduled = true;
        try {
            executor.execute("graph wave", () -> {
                waveScheduled = false;
                if (!graph.isClosedPlain()) {
                    doRunWaves(triggeredBy);
                }
            });
        } catch (RejectedExecutionException e) {
            waveScheduled = false;
            // A rejection that panicked the executor's owning application takes this graph down with that application.
            if (!(e instanceof QueueFullException queueFull && queueFull.ownerPanicked())) {
                panic(WAVE_REJECTED_PANIC_REASON, e);
            }
        }
    }

    /// Runs the graph's waves, on its thread, for a wave task [#scheduleNewWave] queued.
    protected abstract void doRunWaves(String triggeredBy);

    public final void panic(String reason) {
        panic(reason, null);
    }

    /// Panics the graph, unless it has already closed: a task queued for a graph that a panic has since replaced can still reach this. Call it on the graph's
    /// thread.
    ///
    /// @param message why, or `null` when `cause` says it
    /// @param cause   the failure behind the panic, or `null` when there is none
    public final void panic(@Nullable String message, @Nullable Throwable cause) {
        graph.assertCallingThreadConsistent();
        if (graph.isClosed()) {
            logger.debug("Ignoring a panic reaching a closed graph: {}", message);
            return;
        }
        onPanic(message, cause);
    }

    /// Handles a panic of the open graph, on its thread.
    ///
    /// @param message why, or `null` when `cause` says it
    /// @param cause   the failure behind the panic, or `null` when there is none
    protected abstract void onPanic(@Nullable String message, @Nullable Throwable cause);
}
