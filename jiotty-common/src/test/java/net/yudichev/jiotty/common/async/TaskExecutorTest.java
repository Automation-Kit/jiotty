package net.yudichev.jiotty.common.async;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExecutorTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final RejectingSchedulingExecutor executor = new RejectingSchedulingExecutor(clock.createSingleThreadedSchedulingExecutor("test"));

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
}
