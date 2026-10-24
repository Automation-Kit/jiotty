package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.MutableReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final RejectingSchedulingExecutor executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("test"));
    private final SchedulingExecutor plainExecutor = clock.createSingleThreadedSchedulingExecutor("plain");
    private final List<String> continuationsRun = new ArrayList<>();

    @Test
    void submitOrFailCompletesWithTheTaskResult() {
        CompletableFuture<String> result = executor.submitOrFail(() -> "done");
        clock.tick();

        assertThat(result).isCompletedWithValue("done");
    }

    @Test
    void submitOrFailFailsTheFutureWhenTheTaskIsRejected() {
        executor.fillQueue();

        CompletableFuture<String> result = executor.submitOrFail(() -> "done");

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void submitOrFailRunsACommand() {
        List<String> ran = new ArrayList<>();

        CompletableFuture<Void> result = executor.submitOrFail(() -> {
            ran.add("command");
        });
        clock.tick();

        assertThat(result).isCompleted();
        assertThat(ran).containsExactly("command");
    }

    @Test
    void submitOrFailFailsTheFutureWhenTheCommandIsRejected() {
        executor.fillQueue();

        CompletableFuture<Void> result = executor.submitOrFail(() -> {});

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void handleWhileHandsEitherOutcomeToTheContinuationOnTheExecutor() {
        var failure = new IllegalStateException("failed");

        CompletableFuture<String> successOutcome = plainExecutor.handleWhile(() -> true, completedFuture("value"), "task", this::describeOutcome);
        CompletableFuture<String> failureOutcome = plainExecutor.handleWhile(() -> true, failedFuture(failure), "task", this::describeOutcome);
        assertThat(successOutcome).as("the continuation waits for the executor").isNotDone();
        clock.tick();

        assertThat(successOutcome).isCompletedWithValue("value");
        assertThat(failureOutcome).isCompletedWithValue("failed");
    }

    @Test
    void handleWhileConditionNotHoldingNeverCompletesAndRunsNothing() {
        CompletableFuture<String> outcome = plainExecutor.handleWhile(() -> false, completedFuture("value"), "task", this::describeOutcome);
        clock.tick();

        assertThat(outcome).isNotDone();
        assertThat(continuationsRun).isEmpty();
    }

    /// The condition is read when the task runs, so a condition that stops holding between the source's completion and the task skips the continuation.
    @Test
    void handleWhileReadsTheConditionWhenTheTaskRuns() {
        var conditionHolds = new MutableReference<>(true);
        var source = new CompletableFuture<String>();
        CompletableFuture<String> outcome = plainExecutor.handleWhile(conditionHolds::get, source, "task", this::describeOutcome);

        source.complete("value");
        conditionHolds.set(false);
        clock.tick();

        assertThat(outcome).isNotDone();
        assertThat(continuationsRun).isEmpty();
    }

    @Test
    void handleWhileOnAShutDownExecutorNeverCompletesAndRunsNothing() {
        plainExecutor.close();

        CompletableFuture<String> outcome = plainExecutor.handleWhile(() -> true, completedFuture("value"), "task", this::describeOutcome);
        clock.tick();

        assertThat(outcome).isNotDone();
        assertThat(continuationsRun).isEmpty();
    }

    @Test
    void handleWhileConditionThrowingFailsWithTheException() {
        var conditionFailure = new IllegalStateException("condition failed");

        CompletableFuture<String> outcome = plainExecutor.handleWhile(() -> {
            throw conditionFailure;
        }, completedFuture("value"), "task", this::describeOutcome);
        clock.tick();

        assertThat(outcome).failsWithin(Duration.ZERO).withThrowableThat().havingCause().isSameAs(conditionFailure);
    }

    @Test
    void handleWhileCancelledLeavesTheSourceRunning() {
        var source = new CompletableFuture<String>();
        CompletableFuture<String> outcome = plainExecutor.handleWhile(() -> true, source, "task", this::describeOutcome);

        outcome.cancel(true);

        assertThat(source).isNotDone();
        source.complete("value");
        assertThat(source).isCompletedWithValue("value");
    }

    /// @param value   the source's value, or `null` when the source failed
    /// @param failure the source's failure, or `null` when the source succeeded
    private CompletableFuture<String> describeOutcome(@Nullable String value, @Nullable Throwable failure) {
        continuationsRun.add("continuation");
        return completedFuture(failure == null ? value : failure.getMessage());
    }
}
