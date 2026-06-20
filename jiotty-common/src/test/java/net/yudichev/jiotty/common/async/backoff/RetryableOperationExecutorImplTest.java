package net.yudichev.jiotty.common.async.backoff;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetryableOperationExecutorImplTest {
    private static final Duration BACKOFF = Duration.ofSeconds(1);

    @Mock
    private BackingOffExceptionHandler exceptionHandler;
    private ProgrammableClock clock;
    private RetryableOperationExecutorImpl executor;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        var retryExecutor = clock.createSingleThreadedSchedulingExecutor("test");
        executor = new RetryableOperationExecutorImpl(() -> exceptionHandler, () -> retryExecutor);
    }

    @Test
    void retriesAfterScheduledBackoff_withoutBlocking() {
        when(exceptionHandler.handle(any(), any())).thenReturn(Optional.of(BACKOFF.toMillis()));
        var attempts = new MutableReference<>(0);
        Supplier<CompletableFuture<String>> action = () -> {
            attempts.set(attempts.get() + 1);
            return attempts.get() == 1 ? failure(new RuntimeException("transient")) : CompletableFuture.completedFuture("ok");
        };

        var result = executor.withBackOffAndRetry("op", action);
        clock.tick(); // run the first attempt on the retry executor

        // first attempt failed; the retry is scheduled (not slept), so nothing runs until the clock advances
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(result).isNotCompleted();

        clock.advanceTimeAndTick(BACKOFF);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result).isCompletedWithValue("ok");
    }

    @Test
    void cancellingResult_cancelsScheduledRetry() {
        when(exceptionHandler.handle(any(), any())).thenReturn(Optional.of(BACKOFF.toMillis()));
        var attempts = new MutableReference<>(0);
        Supplier<CompletableFuture<String>> action = () -> {
            attempts.set(attempts.get() + 1);
            return failure(new RuntimeException("transient"));
        };

        var result = executor.withBackOffAndRetry("op", action);
        clock.tick(); // run the first attempt; it fails and schedules a retry
        assertThat(attempts.get()).isEqualTo(1);

        result.cancel(true);
        clock.advanceTimeAndTick(BACKOFF);

        assertThat(result).isCancelled();
        assertThat(attempts.get()).as("scheduled retry must not run after cancellation").isEqualTo(1);
    }

    @Test
    void nonRetryableException_failsWithoutRetry() {
        when(exceptionHandler.handle(any(), any())).thenReturn(Optional.empty());
        var permanent = new RuntimeException("permanent");
        var attempts = new MutableReference<>(0);
        Supplier<CompletableFuture<String>> action = () -> {
            attempts.set(attempts.get() + 1);
            return failure(permanent);
        };

        var result = executor.withBackOffAndRetry("op", action);
        clock.tick(); // run the first attempt

        assertThat(attempts.get()).isEqualTo(1);
        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(permanent);
    }

    @Test
    void givesUp_failsWithHandlerException() {
        var giveUp = new IllegalStateException("retried too long");
        when(exceptionHandler.handle(any(), any())).thenThrow(giveUp);

        var result = executor.withBackOffAndRetry("op", () -> failure(new RuntimeException("transient")));
        clock.tick(); // run the first attempt

        assertThatThrownBy(result::join).isInstanceOf(CompletionException.class).hasCause(giveUp);
    }

    @Test
    void successFirstTime_completesWithoutRetry() {
        var result = executor.withBackOffAndRetry("op", () -> CompletableFuture.completedFuture("ok"));
        clock.tick(); // run the first attempt
        assertThat(result).isCompletedWithValue("ok");
    }
}
