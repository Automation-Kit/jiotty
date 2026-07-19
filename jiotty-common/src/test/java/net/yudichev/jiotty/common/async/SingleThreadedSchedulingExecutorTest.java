package net.yudichev.jiotty.common.async;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleThreadedSchedulingExecutorTest {
    private ListenerBackedTaskExceptionHandlerRegistry exceptionHandler;
    private SingleThreadedSchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        exceptionHandler = new ListenerBackedTaskExceptionHandlerRegistry();
        executor = new SingleThreadedSchedulingExecutor("test", exceptionHandler);
    }

    @AfterEach
    void tearDown() {
        closeIfNotNull(executor);
    }

    @Test
    void notifiesExceptionHandlerWhenExecutedTaskFails() {
        var captured = new CompletableFuture<Throwable>();
        exceptionHandler.addExceptionHandler((_, throwable) -> captured.complete(throwable));
        var failure = new RuntimeException("boom");

        executor.execute(() -> {
            throw failure;
        });

        assertThat(captured).succeedsWithin(Duration.ofSeconds(10)).isSameAs(failure);
    }

    @Test
    void rejectsAndCountsTasksWhenQueueIsFull() {
        var registry = new SimpleMeterRegistry();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var boundedExecutor = new SingleThreadedSchedulingExecutor("bounded", "bounded", 2, exceptionHandler, registry)) {
            // Occupy the single thread so nothing drains, then fill the queue to its bound of 2.
            boundedExecutor.execute(() -> {
                started.countDown();
                awaitUninterruptibly(release);
            });
            awaitUninterruptibly(started);
            boundedExecutor.execute(() -> {});
            boundedExecutor.execute(() -> {});

            assertThatThrownBy(() -> boundedExecutor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
            assertThat(registry.get("executor.rejected").tags("name", "bounded", "family", "bounded", "reason", "queue_full").counter().count())
                    .isEqualTo(1.0);
            release.countDown();
        }
    }

    @Test
    void scheduledTasksDoNotConsumeTheImmediateBound() {
        try (var boundedExecutor = new SingleThreadedSchedulingExecutor("sched", "sched", 2, exceptionHandler, null)) {
            // Ten far-future scheduled tasks occupy the shared JDK DelayedWorkQueue but must not count against the immediate bound of 2.
            for (int i = 0; i < 10; i++) {
                boundedExecutor.schedule(Duration.ofHours(1), () -> {});
            }
            var ran = new CompletableFuture<Boolean>();
            boundedExecutor.execute(() -> ran.complete(true));

            assertThat(ran).as("an immediate task is accepted despite 10 pending scheduled tasks").succeedsWithin(Duration.ofSeconds(10)).isEqualTo(true);
        }
    }

    @Test
    void registersExecutorMetricsAndRemovesThemOnClose() {
        var registry = new SimpleMeterRegistry();
        var meteredExecutor = new SingleThreadedSchedulingExecutor("metered", "fam", 100, exceptionHandler, registry);

        assertThat(registry.find("executor.queued").tags("name", "metered", "family", "fam").gauge())
                .as("executor gauges are registered while live")
                .isNotNull();

        meteredExecutor.close();

        assertThat(registry.find("executor.queued").tags("name", "metered", "family", "fam").gauge())
                .as("executor meters are removed on close")
                .isNull();
    }
}
