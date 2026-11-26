package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A [GraphRunner] for node tests: [#runWaves()] queues a wave on the executor, and a panic fails the test unless [#expectPanics()] was called.
public final class TestGraphRunner extends GraphRunner {
    private static final Logger logger = LogManager.getLogger(TestGraphRunner.class);

    private @Nullable String panicReason;
    private boolean closeGraphOnPanic;
    private boolean panicsExpected;

    public TestGraphRunner(Graph graph, SchedulingExecutor executor) {
        super(graph, executor);
    }

    /// @return the reason of the last panic, or `null` when none has happened
    public @Nullable String panicReason() {
        return panicReason;
    }

    /// Makes [#onPanic] close the graph before failing, as the reset of a [BaseGraphBasedServer] does, so a test can observe the effects of that close.
    public void closeGraphOnPanic() {
        closeGraphOnPanic = true;
    }

    /// Records each panic in [#panicReason()] instead of failing the test.
    public void expectPanics() {
        panicsExpected = true;
    }

    @Override
    protected void doRunWaves(String triggeredBy) {
        assertNotInUnexpectedPanic();
        logger.info("New wave triggered at least by {}", triggeredBy);
        graph().runWaves();
    }

    public void runWaves() {
        assertNotInUnexpectedPanic();
        scheduleNewWave("test");
    }

    private void assertNotInUnexpectedPanic() {
        if (!panicsExpected) {
            assertThat(panicReason).as("cannot run, in panic").isNull();
        }
    }

    @Override
    protected void onPanic(@Nullable String message, @Nullable Throwable cause) {
        panicReason = message != null ? message : (cause != null ? cause.toString() : "Unknown panic");
        if (closeGraphOnPanic) {
            graph().close();
        }
        if (!panicsExpected) {
            fail(panicReason, cause);
        }
    }
}
