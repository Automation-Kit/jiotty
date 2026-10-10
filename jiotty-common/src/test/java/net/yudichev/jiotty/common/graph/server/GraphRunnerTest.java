package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.graph.Node;
import net.yudichev.jiotty.common.graph.NodeContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.graph.server.GraphRunner.WAVE_REJECTED_PANIC_REASON;
import static org.assertj.core.api.Assertions.assertThat;

class GraphRunnerTest {
    private final List<String> events = new ArrayList<>();
    private final ProgrammableClock clock = new ProgrammableClock();
    private final RejectingSchedulingExecutor executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("graph"));
    private final Graph graph = new Graph(clock, e -> {throw e;});
    private final RecordingGraphRunner runner = new RecordingGraphRunner(graph, executor);

    @Test
    void aPanicReachingAnOpenGraphIsRaised() {
        runner.panic("boom");

        assertThat(events).containsExactly("panic: boom");
    }

    /// A task queued for a graph that a panic has since replaced can reach the closed one; panicking again would tear down its replacement.
    @Test
    void aPanicReachingAClosedGraphIsIgnored() {
        graph.close();

        runner.panic("boom");

        assertThat(events).isEmpty();
    }

    @Test
    void aPanicOnAThreadOtherThanTheGraphsFails() {
        graph.assertCallingThreadConsistent();
        var panicOutcome = new CompletableFuture<@Nullable Throwable>();
        var otherThread = new Thread(() -> {
            try {
                runner.panic("boom");
                panicOutcome.complete(null);
            } catch (Throwable e) {
                panicOutcome.complete(e);
            }
        });

        otherThread.start();

        assertThat(panicOutcome).succeedsWithin(Duration.ofSeconds(10)).isInstanceOf(AssertionError.class);
        assertThat(events).isEmpty();
    }

    @Test
    void aWaveTheExecutorRejectsPanicsTheGraph() {
        executor.fillQueue();

        runner.scheduleNewWave("trigger");

        assertThat(events).containsExactly("panic: " + WAVE_REJECTED_PANIC_REASON);
    }

    /// The application owning the executor is restarting, which takes the graph down with it.
    @Test
    void aWaveRejectedByAnExecutorWhoseOwnerPanickedLeavesTheGraphAlone() {
        executor.fillQueueOfPanickedOwner();

        runner.scheduleNewWave("trigger");

        assertThat(events).isEmpty();
    }

    @Test
    void aWaveRequestedAfterARejectedOneIsQueued() {
        executor.fillQueue();
        runner.scheduleNewWave("rejected");
        executor.emptyQueue();

        runner.scheduleNewWave("requested again");
        clock.tick();

        assertThat(events).containsExactly("panic: " + WAVE_REJECTED_PANIC_REASON, "wave: requested again");
    }

    @Test
    void wavesRequestedBeforeOneRunsShareIt() {
        runner.scheduleNewWave("first");
        runner.scheduleNewWave("second");
        clock.tick();

        assertThat(events).containsExactly("wave: first");
    }

    @Test
    void aWaveRequestedAfterOneRanIsQueuedAgain() {
        runner.scheduleNewWave("first");
        clock.tick();
        runner.scheduleNewWave("second");
        clock.tick();

        assertThat(events).containsExactly("wave: first", "wave: second");
    }

    @Test
    void noWaveIsQueuedOnceTheGraphHasClosed() {
        graph.close();

        runner.scheduleNewWave("trigger");
        clock.tick();

        assertThat(events).isEmpty();
    }

    @Test
    void aWaveQueuedBeforeTheGraphClosedDoesNotRun() {
        runner.scheduleNewWave("trigger");
        graph.close();
        clock.tick();

        assertThat(events).isEmpty();
    }

    /// The wave in progress runs every node a trigger inside it marks, so a trigger there needs no further wave.
    @Test
    void noWaveIsQueuedFromInsideAWave() {
        graph.registerNode("trigger inside a wave", new Node() {
            @Override
            public void initialise(NodeContext nodeContext) {
            }

            @Override
            public boolean wave() {
                runner.scheduleNewWave("inside a wave");
                return false;
            }
        });

        runner.scheduleNewWave("trigger");
        clock.tick();

        assertThat(events).containsExactly("wave: trigger");
    }

    @Test
    void closingTheRunnerClosesTheGraph() {
        runner.close();

        assertThat(graph.isClosed()).isTrue();
    }

    private final class RecordingGraphRunner extends GraphRunner {
        private RecordingGraphRunner(Graph graph, SchedulingExecutor executor) {
            super(graph, executor);
        }

        @Override
        protected void doRunWaves(String triggeredBy) {
            events.add("wave: " + triggeredBy);
            graph().runWaves();
        }

        @Override
        protected void onPanic(@Nullable String message, @Nullable Throwable cause) {
            events.add("panic: " + message);
        }
    }
}
