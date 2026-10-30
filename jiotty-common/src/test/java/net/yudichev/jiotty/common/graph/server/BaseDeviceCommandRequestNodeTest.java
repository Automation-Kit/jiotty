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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

class BaseDeviceCommandRequestNodeTest {
    private static final Duration RETRY_DELAY = Duration.ofMinutes(10);
    private static final int MAX_RETRIES = 5;

    private ProgrammableClock clock;
    private TestGraphRunner graphRunner;
    private TestStateNode stateNode;
    private TestCommandRequestNode requestNode;
    private ThrowingHooksRequestNode throwingNode;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc();
        graphRunner = new TestGraphRunner(new Graph(clock, Assertions::fail), clock.createSingleThreadedSchedulingExecutor("test"));
        stateNode = new TestStateNode(graphRunner);
        requestNode = new TestCommandRequestNode(graphRunner, stateNode);
        throwingNode = new ThrowingHooksRequestNode(graphRunner, stateNode);
        stateNode.registerInGraph();
        requestNode.registerInGraph();
        throwingNode.registerInGraph();
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

    /// The hook says this node changed the device, so it must not fire for a device that was already in the requested state — nothing was sent, and a caller
    /// attributing the state to its own command would be crediting itself with somebody else's doing.
    @Test
    void deviceAlreadyInRequestedState_doesNotReportTheCommandAsConfirmed() {
        stateNode.set("ON");

        requestNode.request("ON");
        runWave();

        assertThat(requestNode.confirmations).isEmpty();
    }

    @Test
    void aSentCommandTheDeviceConfirms_reportsItOnceWhenTheStateArrives() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        // Sent, and not yet confirmed: the device has not reached the requested state.
        assertThat(requestNode.confirmations).isEmpty();

        stateNode.set("ON");
        runWave();
        assertThat(requestNode.confirmations).containsExactly("ON");

        // The request is complete, so a later wave must not report it again.
        runWave();
        assertThat(requestNode.confirmations).containsExactly("ON");
    }

    /// The request is cleared before the hook runs, so a hook that throws cannot leave it pending and have the next wave send the command a second time.
    /// Ordering is the whole of it: swap the two statements and this node would re-command a device that had already done what was asked.
    @Test
    void aConfirmationHookThatThrows_leavesTheRequestCompleteAndSendsNoSecondCommand() {
        stateNode.set("OFF");
        throwingNode.request("ON");
        runWave();
        assertThat(throwingNode.commandsSent).containsExactly("ON");

        stateNode.set("ON");
        assertThatThrownBy(this::runWave).hasRootCauseInstanceOf(IllegalStateException.class);

        // The throw escaped, but the request had already been cleared: a later wave finds nothing pending and sends nothing.
        runWave();
        assertThat(throwingNode.commandsSent).containsExactly("ON");
        assertThat(throwingNode.requestPending()).isFalse();
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
    void retryTriggerOfAForgottenRequest_leavesTheRequestMadeAfterItAlone() {
        var triggerNode = new RetryTriggerRequestNode(graphRunner, stateNode, 0);
        triggerNode.registerInGraph();
        stateNode.set("OFF");
        runWave();
        triggerNode.request("ON");
        runWave();
        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();
        triggerNode.forgetRequest();
        runWave();

        triggerNode.request("ON");
        runWave();
        int sentBeforeStaleTrigger = triggerNode.commandsSent.size();
        triggerNode.fireRetryTrigger();
        clock.tick();

        assertThat(triggerNode.commandsSent).hasSize(sentBeforeStaleTrigger);
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

    @Test
    void retriesExhaustedWithoutPanic_givesTheRequestUpOnceAndClearsIt() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();

        letEveryRetryFallDue();

        assertThat(requestNode.commandsSent).hasSize(1 + MAX_RETRIES);
        assertThat(requestNode.finalFailures).containsExactly("Retry count exceeded: device state did not confirm that the command was successful");
        assertThat(requestNode.requestPending()).isFalse();

        clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
        runWave();
        assertThat(requestNode.commandsSent).hasSize(1 + MAX_RETRIES);
        assertThat(requestNode.finalFailures).hasSize(1);
    }

    @Test
    void givingUp_carriesTheLastFailureTheCommandReported() {
        stateNode.set("OFF");
        requestNode.failureToReport = "device refused";
        requestNode.request("ON");
        runWave();

        letEveryRetryFallDue();

        assertThat(requestNode.finalFailures).containsExactly("device refused");
    }

    /// A request left pending after being given up would make the node ignore every later request with the same payload as already in progress.
    @Test
    void aRequestAfterGivingUp_isSentAfresh() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        letEveryRetryFallDue();

        requestNode.request("ON");
        runWave();

        assertThat(requestNode.commandsSent).hasSize(2 + MAX_RETRIES);
        assertThat(requestNode.requestPending()).isTrue();
    }

    @Test
    void aGiveUpHookThatThrows_leavesTheRequestCleared() {
        stateNode.set("OFF");
        throwingNode.request("ON");
        runWave();

        letRetriesFallDue(MAX_RETRIES);
        // the retry that finds the retries spent waves the node from its own schedule
        assertThatThrownBy(() -> clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1))).hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(throwingNode.requestPending()).isFalse();
    }

    @Test
    void requestSucceeded_isReportedWhenASentCommandIsConfirmed() {
        stateNode.set("OFF");
        requestNode.request("ON");
        runWave();
        assertThat(requestNode.successes).isZero();

        stateNode.set("ON");
        runWave();

        assertThat(requestNode.successes).isEqualTo(1);
    }

    @Test
    void requestSucceeded_isReportedWhenTheDeviceIsAlreadyInTheRequestedState() {
        stateNode.set("ON");

        requestNode.request("ON");
        runWave();

        assertThat(requestNode.successes).isEqualTo(1);
    }

    @Test
    void retriesExhaustedWithPanic_panicsAndKeepsTheRequestAsFailed() {
        graphRunner.panicsExpected = true;
        var panickingNode = new PlainRequestNode(graphRunner, stateNode, true);
        panickingNode.registerInGraph();
        stateNode.set("OFF");
        panickingNode.request("ON");
        runWave();

        letEveryRetryFallDue();

        assertThat(graphRunner.panicMessage).contains("retried " + MAX_RETRIES + " times");
        assertThat(panickingNode.currentRequest()).isNotNull().satisfies(request -> assertThat(request.toString()).contains("FAILED"));
    }

    @Test
    void requestWithoutHooks_isGivenUpAndCompletesOnTheBasesOwnDefaults() {
        var plainNode = new PlainRequestNode(graphRunner, stateNode, false);
        plainNode.registerInGraph();
        stateNode.set("OFF");
        plainNode.request("ON");
        runWave();
        letEveryRetryFallDue();
        assertThat(plainNode.requestPending()).isFalse();

        plainNode.request("ON");
        runWave();
        stateNode.set("ON");
        runWave();

        assertThat(plainNode.requestPending()).isFalse();
    }

    private void letEveryRetryFallDue() {
        // the retries, then the one that finds them spent
        letRetriesFallDue(MAX_RETRIES + 1);
    }

    private void letRetriesFallDue(int count) {
        for (int attempt = 0; attempt < count; attempt++) {
            clock.advanceTimeAndTick(RETRY_DELAY.plusMinutes(1));
            runWave();
        }
    }

    private void runWave() {
        graphRunner.scheduleNewWave("test");
        clock.tick();
    }

    private static final class TestGraphRunner extends GraphRunner {
        /// Whether a panic is recorded rather than failing the test, for a test that expects one.
        boolean panicsExpected;
        /// The message of the last recorded panic, or `null` when none has been recorded or the panic carried no message.
        @Nullable String panicMessage;

        TestGraphRunner(Graph graph, SchedulingExecutor executor) {
            super(graph, executor);
        }

        @Override
        protected void doRunWaves(String triggeredBy) {
            graph().runWaves();
        }

        @Override
        protected void onPanic(@Nullable String message, @Nullable Throwable cause) {
            if (panicsExpected) {
                panicMessage = message;
            } else {
                fail(message != null ? message : String.valueOf(cause), cause);
            }
        }
    }

    /// A request node that records every command it sends and counts a request done once [TestStateNode] holds the requested payload.
    private abstract static class StateMatchingRequestNode extends BaseDeviceCommandRequestNode<String> {
        final List<String> commandsSent = new ArrayList<>();
        private final TestStateNode stateNode;

        StateMatchingRequestNode(GraphRunner runner, String name, TestStateNode stateNode, int maxRetriesBeforeFatalFailure, boolean panicOnFatalFailure) {
            super(runner, name, RETRY_DELAY, maxRetriesBeforeFatalFailure, panicOnFatalFailure);
            this.stateNode = subscribeTo(stateNode);
        }

        void request(String payload) {
            createRequestIfNotAlreadyInProgress(() -> new DeviceRequest<>("Set state", payload));
        }

        @Override
        protected boolean deviceStateValidForRequestToBeSent() {
            return !"UNKNOWN".equals(deviceState());
        }

        @Override
        protected boolean deviceStateIndicatesRequestSuccessful(String payload) {
            return payload.equals(deviceState());
        }

        String deviceState() {
            return stateNode.state();
        }

        @Override
        protected void sendCommand(int retryNumber, String payload, Consumer<String> failureHandler) {
            commandsSent.add(payload);
        }
    }

    /// A request node that overrides none of [BaseDeviceCommandRequestNode]'s hooks, so a test exercises the defaults [BaseDeviceCommandRequestNode]
    /// declares.
    private static final class PlainRequestNode extends StateMatchingRequestNode {
        PlainRequestNode(GraphRunner runner, TestStateNode stateNode, boolean panicOnFatalFailure) {
            super(runner, "PlainRequest", stateNode, MAX_RETRIES, panicOnFatalFailure);
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

    private static final class TestCommandRequestNode extends StateMatchingRequestNode {
        final List<String> confirmations = new ArrayList<>();
        final List<String> finalFailures = new ArrayList<>();
        int successes;
        /// Reported as the failure of every command sent, or `null` to report none.
        @Nullable String failureToReport;

        TestCommandRequestNode(GraphRunner runner, TestStateNode stateNode) {
            super(runner, "TestCommandRequest", stateNode, MAX_RETRIES, false);
        }

        @Override
        protected void sendCommand(int retryNumber, String payload, Consumer<String> failureHandler) {
            super.sendCommand(retryNumber, payload, failureHandler);
            if (failureToReport != null) {
                failureHandler.accept(failureToReport);
            }
        }

        @Override
        protected void onSentCommandConfirmed() {
            confirmations.add(deviceState());
        }

        @Override
        protected void onRequestFailedFinally(DeviceRequest<String> failedRequest, String failure) {
            finalFailures.add(failure);
        }

        @Override
        protected void onRequestSucceeded() {
            successes++;
        }
    }

    /// A request node that throws from [#onSentCommandConfirmed] and [#onRequestFailedFinally], so a test can see what state the request is left in when an
    /// override of either hook fails.
    private static final class ThrowingHooksRequestNode extends StateMatchingRequestNode {
        ThrowingHooksRequestNode(GraphRunner runner, TestStateNode stateNode) {
            super(runner, "ThrowingHooksRequest", stateNode, MAX_RETRIES, false);
        }

        @Override
        protected void onSentCommandConfirmed() {
            throw new IllegalStateException("hook failed");
        }

        @Override
        protected void onRequestFailedFinally(DeviceRequest<String> failedRequest, String failure) {
            throw new IllegalStateException("hook failed");
        }
    }

    /// A request node that retains its retry trigger on fatal failure, so the trigger-based retry path can be driven.
    private static final class RetryTriggerRequestNode extends StateMatchingRequestNode {
        private @Nullable Runnable retryTrigger;

        RetryTriggerRequestNode(GraphRunner runner, TestStateNode stateNode, int maxRetriesBeforeFatalFailure) {
            super(runner, "RetryTriggerRequest", stateNode, maxRetriesBeforeFatalFailure, false);
        }

        void fireRetryTrigger() {
            assert retryTrigger != null : "no retry trigger captured";
            retryTrigger.run();
        }

        @Override
        protected boolean onCommandFailedFatally(String lastFailure, Runnable retryTrigger) {
            this.retryTrigger = retryTrigger;
            return true;
        }
    }
}
