package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.RejectingSchedulingExecutor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static net.yudichev.jiotty.common.graph.server.BaseGraphBasedServer.HEALTHY_PERIOD_BEFORE_BACKOFF_RESET;
import static net.yudichev.jiotty.common.graph.server.BaseGraphBasedServer.INITIAL_REINIT_DELAY;
import static net.yudichev.jiotty.common.graph.server.BaseGraphBasedServer.MAX_PANICKING_PERIOD_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;

class BaseGraphBasedServerTest {
    private ProgrammableClock clock;
    private RejectingSchedulingExecutor executor;
    private TestServer server;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("test"));
        server = new TestServer();
        server.start();
        clock.tick();
    }

    @Test
    void stopHandsGraphTeardownToTheExecutorAndReturns() {
        server.stop();

        assertThat(server.graphIsActive()).as("stop returns without waiting for the teardown").isTrue();

        // The executor's drain runs the queued teardown, as it does at shutdown
        executor.close();

        assertThat(server.graphIsActive()).as("the drain closes the graph").isFalse();
    }

    @Test
    void waveQueuedBeforeStopDoesNotRunAfterIt() {
        int recordStateCallsBeforeStop = server.recordStateCalls;

        server.runner().scheduleNewWave("test");
        server.stop();
        clock.tick();

        assertThat(server.recordStateCalls).as("a wave dequeued after stop returns without running").isEqualTo(recordStateCallsBeforeStop);
    }

    @Test
    void graphIsNotCreatedWhenStopPrecedesTheQueuedCreation() {
        var stoppedBeforeCreation = new TestServer();
        stoppedBeforeCreation.start();
        stoppedBeforeCreation.stop();

        clock.tick();

        assertThat(stoppedBeforeCreation.createNodesTimes).as("the queued graph creation returns without running").isEmpty();
    }

    @Test
    void panic_messageOnly_handlePanicReceivesMessageAndNullCause() {
        server.runner().panic("disk full", null);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("disk full");
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("disk full");
    }

    @Test
    void panic_causeOnly_handlePanicReceivesNullMessageAndCause() {
        var cause = new IllegalStateException("boom");
        server.runner().panic(null, cause);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isNull();
            assertThat(call.cause).isSameAs(cause);
        });
        assertThat(server.panicReason).contains("boom");
    }

    @Test
    void panic_messageAndCause_handlePanicReceivesBoth_panicReasonComposes() {
        var cause = new IllegalStateException("disk gone");
        server.runner().panic("write failed", cause);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("write failed");
            assertThat(call.cause).isSameAs(cause);
        });
        assertThat(server.panicReason).startsWith("write failed").contains("disk gone");
    }

    @Test
    void panic_neitherMessageNorCause_handlePanicReceivesNulls() {
        server.runner().panic(null, null);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isNull();
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("Reason unknown");
    }

    @Test
    void panic_oneArgOverload_delegatesWithNullCause() {
        server.runner().panic("legacy reason");
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("legacy reason");
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("legacy reason");
    }

    @Test
    void aRebuildThatFailsIsRetried() {
        var nodeCreationFailure = new IllegalStateException("queue is full");
        server.nextNodeCreationFailure = nodeCreationFailure;

        server.runner().panic("trigger", null);
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY.multipliedBy(3));

        assertThat(server.handlePanicCalls).extracting(HandlePanicCall::cause).containsExactly(null, nodeCreationFailure);
        assertThat(server.createNodesTimes).hasSize(3);
        assertThat(server.graphIsActive()).isTrue();
    }

    @Test
    void aPanickedGraphIsRebuiltAfterTheInitialReinitDelay() {
        server.runner().panic("trigger", null);
        clock.tick();

        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY.minusMillis(1));
        assertThat(server.graphIsActive()).isFalse();

        clock.advanceTimeAndTick(Duration.ofMillis(1));
        assertThat(server.createNodesTimes).hasSize(2);
        assertThat(server.graphIsActive()).isTrue();
    }

    @Test
    void aGraphThatPanicsInEveryWaveIsRebuiltAtGrowingIntervals() {
        server.recordStateFailure = new IllegalStateException("store unavailable");
        server.runner().scheduleNewWave("test");
        clock.tick();

        // the rebuilt graph's first wave panics too, so the next rebuild waits longer than the first one did
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(2);

        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(2);

        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(3);
    }

    @Test
    void aGraphThatPanicsOutsideAWaveSoonAfterEveryRebuildIsRebuiltAtGrowingIntervals() {
        server.runner().panic("trigger", null);
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(2);

        // the rebuilt graph's first wave is clean, and the panic comes well inside HEALTHY_PERIOD_BEFORE_BACKOFF_RESET
        server.runner().panic("trigger", null);
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(2);

        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        assertThat(server.createNodesTimes).hasSize(3);
    }

    @Test
    void aGraphThatStaysHealthyForTheHealthyPeriodIsRebuiltAfterTheInitialDelayOnItsNextPanic() {
        server.runner().panic("trigger", null);
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);
        clock.advanceTimeAndTick(HEALTHY_PERIOD_BEFORE_BACKOFF_RESET);

        server.runner().panic("trigger", null);
        clock.advanceTimeAndTick(INITIAL_REINIT_DELAY);

        assertThat(server.createNodesTimes).hasSize(3);
    }

    @Test
    void aGraphWhoseFirstWaveCannotBeQueuedIsRebuiltAtGrowingIntervals() {
        executor.fillQueue();
        server.runner().panic("trigger", null);

        // every rebuild panics while queueing its first wave, so none stays up for HEALTHY_PERIOD_BEFORE_BACKOFF_RESET, the gaps grow, and none is shorter
        // than the one before it
        clock.advanceTimeAndTick(HEALTHY_PERIOD_BEFORE_BACKOFF_RESET.multipliedBy(5));

        List<Duration> gapsBetweenRebuilds = new ArrayList<>();
        for (int i = 1; i < server.createNodesTimes.size(); i++) {
            gapsBetweenRebuilds.add(Duration.between(server.createNodesTimes.get(i - 1), server.createNodesTimes.get(i)));
        }
        assertThat(gapsBetweenRebuilds).hasSizeGreaterThan(5).isSorted();
        assertThat(gapsBetweenRebuilds.getLast()).isGreaterThan(gapsBetweenRebuilds.getFirst());
    }

    @Test
    void aGraphThatKeepsPanickingForTheMaxPanickingPeriodIsNotReinitialisedAgain() {
        server.recordStateFailure = new IllegalStateException("store unavailable");
        server.runner().scheduleNewWave("test");
        clock.advanceTimeAndTick(Duration.ofMillis(MAX_PANICKING_PERIOD_MILLIS).plus(HEALTHY_PERIOD_BEFORE_BACKOFF_RESET));
        int rebuildsWhenGivenUp = server.createNodesTimes.size();

        clock.advanceTimeAndTick(Duration.ofDays(1));

        assertThat(server.createNodesTimes).hasSize(rebuildsWhenGivenUp);
        assertThat(server.graphIsActive()).isFalse();
        assertThat(server.panicReason).contains("store unavailable");
    }

    private final class TestServer extends BaseGraphBasedServer {
        final List<HandlePanicCall> handlePanicCalls = new ArrayList<>();
        final List<Instant> createNodesTimes = new ArrayList<>();
        int recordStateCalls;
        /// Thrown by the next node creation, then cleared; `null` lets node creation succeed.
        @Nullable RuntimeException nextNodeCreationFailure;
        /// Thrown by every [#recordState()] call while set, which panics every wave; `null` lets state recording succeed.
        @Nullable RuntimeException recordStateFailure;
        private @Nullable GraphRunner capturedRunner;

        TestServer() {
            super(() -> executor, clock, () -> 0.5);
        }

        GraphRunner runner() {
            return checkRunner();
        }

        boolean graphIsActive() {
            return graphActive();
        }

        @Override
        protected void createNodes(GraphRunner graphRunner, NodeRegistrator registrator) {
            createNodesTimes.add(clock.currentInstant());
            RuntimeException failure = nextNodeCreationFailure;
            if (failure != null) {
                nextNodeCreationFailure = null;
                throw failure;
            }
            capturedRunner = graphRunner;
        }

        @Override
        protected void recordState() {
            recordStateCalls++;
            if (recordStateFailure != null) {
                throw recordStateFailure;
            }
        }

        @Override
        protected void handlePanic(@Nullable String message, @Nullable Throwable cause) {
            handlePanicCalls.add(new HandlePanicCall(message, cause));
        }

        private GraphRunner checkRunner() {
            if (capturedRunner == null) {
                throw new IllegalStateException("Graph not yet created");
            }
            return capturedRunner;
        }
    }

    private record HandlePanicCall(@Nullable String message, @Nullable Throwable cause) {}
}
