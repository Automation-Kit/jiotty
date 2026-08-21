package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class BaseDeviceCommandRequestNodeTest {
    private static final Duration RETRY_DELAY = Duration.ofMinutes(10);

    private ProgrammableClock clock;
    private GraphRunner graphRunner;
    private TestStateNode stateNode;
    private TestCommandRequestNode requestNode;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc();
        graphRunner = new TestGraphRunner(new Graph(clock, Assertions::fail), clock.createSingleThreadedSchedulingExecutor("test"));
        stateNode = new TestStateNode(graphRunner);
        requestNode = new TestCommandRequestNode(graphRunner, stateNode);
        stateNode.registerInGraph();
        requestNode.registerInGraph();
        runWave();
    }

    @Test
    void deviceAlreadyInRequestedState_completesWithoutSendingCommand() {
        stateNode.set("ON");

        requestNode.request("ON");
        runWave();

        assertThat(requestNode.commandsSent).isEmpty();
        assertThat(requestNode.requestPending()).isFalse();
    }

    @Test
    void deviceAlreadyInRequestedState_armsNoRetryWhenTheStateLaterChanges() {
        stateNode.set("ON");
        requestNode.request("ON");
        runWave();

        // the device leaves the requested state; a request left pending would drive it back here
        stateNode.set("OFF");
        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();

        assertThat(requestNode.commandsSent).isEmpty();
    }

    @Test
    void unconfirmedRequest_isRetriedUntilTheDeviceConfirms() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        assertThat(requestNode.commandsSent).containsExactly("ON");

        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();

        assertThat(requestNode.commandsSent).containsExactly("ON", "ON");
    }

    @Test
    void retryFallingDueWhileTheDeviceCannotAcceptIt_isSentOnceItCan() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        assertThat(requestNode.commandsSent).containsExactly("ON");

        // the device stops accepting commands, and the retry falls due while it is in that state
        stateNode.set("UNKNOWN");
        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();
        assertThat(requestNode.commandsSent).as("nothing sent while the device cannot accept it").containsExactly("ON");

        // the device accepts commands again, and the retry that was owed is spent
        stateNode.set("OFF");
        runWave();

        assertThat(requestNode.commandsSent).containsExactly("ON", "ON");
    }

    @Test
    void triggerBasedRetry_isSentEvenWhileTheDeviceCannotAcceptIt() {
        var triggerNode = new RetryTriggerRequestNode(graphRunner, stateNode, 1);
        triggerNode.registerInGraph();
        stateNode.set("OFF");
        runWave();

        triggerNode.request("ON");
        runWave();
        // exhaust the ordinary retries so the request reaches the fatal path, which hands out the retry trigger
        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();
        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();
        int sentBeforeTrigger = triggerNode.commandsSent.size();

        // the trigger-based retry is not one of the counted attempts, so it goes out whatever the device state says
        stateNode.set("UNKNOWN");
        runWave();
        triggerNode.fireRetryTrigger();
        clock.tick();

        assertThat(triggerNode.commandsSent).hasSize(sentBeforeTrigger + 1);
    }

    @Test
    void retriesExhausted_stopAtTheConfiguredMaximum() {
        var triggerNode = new RetryTriggerRequestNode(graphRunner, stateNode, 2);
        triggerNode.registerInGraph();
        stateNode.set("OFF");
        runWave();

        triggerNode.request("ON");
        runWave();
        for (int attempt = 0; attempt < 5; attempt++) {
            clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
            runWave();
        }

        // the initial send plus exactly maxRetriesBeforeFatalFailure retries; the rest await the trigger
        assertThat(triggerNode.commandsSent).containsExactly("ON", "ON", "ON");
    }

    @Test
    void forgetRequest_cancelsTheRetryOfAnUnconfirmedRequest() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        assertThat(requestNode.commandsSent).containsExactly("ON");

        requestNode.forgetRequest();
        runWave();
        assertThat(requestNode.requestPending()).isFalse();

        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();

        assertThat(requestNode.commandsSent).containsExactly("ON");
    }

    @Test
    void forgetRequest_withNothingPending_leavesALaterRequestAlone() {
        stateNode.set("OFF");

        requestNode.forgetRequest();
        requestNode.request("ON");
        runWave();

        assertThat(requestNode.commandsSent).containsExactly("ON");
    }

    @Test
    void requestCreatedBeforeTheForgetWaveRuns_supersedesTheForget() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        assertThat(requestNode.commandsSent).containsExactly("ON");

        // both calls land before the graph waves again, as they do when two nodes act in one batch of waves
        requestNode.forgetRequest();
        requestNode.request("AUTO");
        runWave();

        assertThat(requestNode.commandsSent).containsExactly("ON", "AUTO");
    }

    private void runWave() {
        graphRunner.scheduleNewWave("test");
        clock.tick();
    }

    private static final class TestGraphRunner extends GraphRunner {
        TestGraphRunner(Graph graph, SchedulingExecutor executor) {
            super(graph, executor);
        }

        @Override
        public void scheduleNewWave(String triggeredBy) {
            if (graph().inWave()) {
                return;
            }
            executor().execute(triggeredBy, () -> {
                if (!isClosed()) {
                    graph().runWaves();
                }
            });
        }

        @Override
        public void panic(@Nullable String message, @Nullable Throwable cause) {
            fail(message != null ? message : String.valueOf(cause), cause);
        }
    }

    private static final class TestStateNode extends BaseTestServerNode {
        private String state = "UNKNOWN";

        TestStateNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        String state() {
            return state;
        }

        void set(String state) {
            this.state = state;
            triggerInNextWave();
        }
    }

    private static final class TestCommandRequestNode extends BaseDeviceCommandRequestNode<String> {
        final List<String> commandsSent = new ArrayList<>();
        private final TestStateNode stateNode;

        TestCommandRequestNode(GraphRunner runner, TestStateNode stateNode) {
            super(runner, "TestCommandRequest", RETRY_DELAY, 5, false);
            this.stateNode = subscribeTo(stateNode);
        }

        void request(String payload) {
            createRequestIfNotAlreadyInProgress(() -> new DeviceRequest<>("Set state", payload));
        }

        @Override
        protected boolean deviceStateValidForRequestToBeSent() {
            return !"UNKNOWN".equals(stateNode.state());
        }

        @Override
        protected boolean deviceStateIndicatesRequestSuccessful(String payload) {
            return payload.equals(stateNode.state());
        }

        @Override
        protected void sendCommand(int retryNumber, String payload, Consumer<String> failureHandler) {
            commandsSent.add(payload);
        }
    }

    /// Retains its retry trigger on fatal failure, so the trigger-based retry path can be driven.
    private static final class RetryTriggerRequestNode extends BaseDeviceCommandRequestNode<String> {
        final List<String> commandsSent = new ArrayList<>();
        private final TestStateNode stateNode;
        private @Nullable Runnable retryTrigger;

        RetryTriggerRequestNode(GraphRunner runner, TestStateNode stateNode, int maxRetriesBeforeFatalFailure) {
            super(runner, "RetryTriggerRequest", RETRY_DELAY, maxRetriesBeforeFatalFailure, false);
            this.stateNode = subscribeTo(stateNode);
        }

        void request(String payload) {
            createRequestIfNotAlreadyInProgress(() -> new DeviceRequest<>("Set state", payload));
        }

        void fireRetryTrigger() {
            checkNotNull(retryTrigger, "no retry trigger captured").run();
        }

        @Override
        protected boolean deviceStateValidForRequestToBeSent() {
            return !"UNKNOWN".equals(stateNode.state());
        }

        @Override
        protected boolean deviceStateIndicatesRequestSuccessful(String payload) {
            return payload.equals(stateNode.state());
        }

        @Override
        protected boolean onCommandFailedFatally(String lastFailure, Runnable retryTrigger) {
            this.retryTrigger = retryTrigger;
            return true;
        }

        @Override
        protected void sendCommand(int retryNumber, String payload, Consumer<String> failureHandler) {
            commandsSent.add(payload);
        }
    }
}
