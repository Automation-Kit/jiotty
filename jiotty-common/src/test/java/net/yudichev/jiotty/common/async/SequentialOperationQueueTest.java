package net.yudichev.jiotty.common.async;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.async.ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE;
import static org.assertj.core.api.Assertions.assertThat;

class SequentialOperationQueueTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final List<String> startedOperationNames = new ArrayList<>();
    private SequentialOperationQueue queue;

    @BeforeEach
    void setUp() {
        queue = new SequentialOperationQueue(clock.createSingleThreadedSchedulingExecutor("test", "test", DEFAULT_MAX_QUEUE_SIZE));
    }

    @Test
    void anOperationStartsOnlyOnceThePreviousOneHasFinished() {
        var firstOutcome = new CompletableFuture<String>();
        CompletableFuture<String> first = submit("first", firstOutcome);
        CompletableFuture<String> second = submit("second", CompletableFuture.completedFuture("second done"));
        clock.tick();
        assertThat(startedOperationNames).containsExactly("first");

        firstOutcome.complete("first done");
        clock.tick();

        assertThat(startedOperationNames).containsExactly("first", "second");
        assertThat(first).succeedsWithin(Duration.ZERO).isEqualTo("first done");
        assertThat(second).succeedsWithin(Duration.ZERO).isEqualTo("second done");
    }

    @Test
    void aFailedOperationStillLetsTheNextOneRunAndKeepsItsFailureToItself() {
        CompletableFuture<String> first = submit("first", CompletableFuture.failedFuture(new IllegalStateException("boom")));
        CompletableFuture<String> second = submit("second", CompletableFuture.completedFuture("second done"));
        clock.tick();

        assertThat(first).failsWithin(Duration.ZERO).withThrowableThat().withRootCauseInstanceOf(IllegalStateException.class);
        assertThat(second).succeedsWithin(Duration.ZERO).isEqualTo("second done");
    }

    /// A caller giving up on its result must not let the next operation overlap the one still running.
    @Test
    void cancellingAnOperationsResultStillHoldsTheNextOneBack() {
        var firstOutcome = new CompletableFuture<String>();
        CompletableFuture<String> first = submit("first", firstOutcome);
        clock.tick();

        first.cancel(true);
        submit("second", CompletableFuture.completedFuture("second done"));
        clock.tick();
        assertThat(startedOperationNames).containsExactly("first");

        firstOutcome.complete("first done");
        clock.tick();
        assertThat(startedOperationNames).containsExactly("first", "second");
    }

    @Test
    void anOperationStartsOnTheExecutor() {
        CompletableFuture<String> operation = submit("only", CompletableFuture.completedFuture("done"));
        assertThat(startedOperationNames).isEmpty();

        clock.tick();

        assertThat(startedOperationNames).containsExactly("only");
        assertThat(operation).succeedsWithin(Duration.ZERO);
    }

    private CompletableFuture<String> submit(String name, CompletableFuture<String> outcome) {
        return queue.submit(() -> {
            startedOperationNames.add(name);
            return outcome;
        });
    }
}
