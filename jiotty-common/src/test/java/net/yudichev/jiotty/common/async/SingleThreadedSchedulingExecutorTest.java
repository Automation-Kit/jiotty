package net.yudichev.jiotty.common.async;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleThreadedSchedulingExecutorTest {
    private static final Logger logger = LogManager.getLogger(SingleThreadedSchedulingExecutorTest.class);

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
    void reportsFailedTaskUnderTheNameItWasSubmittedWith() {
        var capturedName = new CompletableFuture<String>();
        exceptionHandler.addExceptionHandler((taskName, _) -> capturedName.complete(taskName));

        executor.execute("teardown of SomeComponent", () -> {
            throw new RuntimeException("boom");
        });

        assertThat(capturedName).succeedsWithin(Duration.ofSeconds(10)).isEqualTo("teardown of SomeComponent");
    }

    @Test
    void closesResourcesOnTheExecutorThread() {
        var closingThread = new CompletableFuture<Thread>();

        executor.executeClose("teardown", logger, () -> closingThread.complete(Thread.currentThread()));

        assertThat(closingThread).succeedsWithin(Duration.ofSeconds(10))
                                 .satisfies(thread -> assertThat(thread.getName()).startsWith("test-"));
    }

    @Test
    void closesEveryResourceEvenWhenOneFails() {
        var secondClosed = new CompletableFuture<Void>();

        executor.executeClose("teardown", logger, () -> {
            throw new RuntimeException("boom");
        }, () -> secondClosed.complete(null));

        assertThat(secondClosed).succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    void rejectsAndCountsTasksWhenQueueIsFull() {
        var registry = new SimpleMeterRegistry();
        try (var boundedExecutor = new SingleThreadedSchedulingExecutor("bounded", "bounded", 2, exceptionHandler, registry)) {
            // Occupy the single thread so nothing drains, then fill the queue to its bound of 2.
            CountDownLatch release = occupy(boundedExecutor);
            boundedExecutor.execute(() -> {});
            boundedExecutor.execute(() -> {});

            assertThatThrownBy(() -> boundedExecutor.execute(() -> {})).isInstanceOf(RejectedExecutionException.class);
            assertThat(registry.get("executor.rejected").tags("name", "bounded", "family", "bounded", "reason", "queue_full").counter().count())
                    .isEqualTo(1.0);
            release.countDown();
        }
    }

    @Test
    void rejectsNonPositiveMaxQueueSize() {
        assertThatThrownBy(() -> new SingleThreadedSchedulingExecutor("x", "x", 0)).isInstanceOf(IllegalArgumentException.class);
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
    void flushesFollowUpScheduledDuringShutdownWithoutRejection() {
        var registry = new SimpleMeterRegistry();
        var rejections = new CopyOnWriteArrayList<Throwable>();
        exceptionHandler.addExceptionHandler((_, throwable) -> rejections.add(throwable));
        try (var meteredExecutor = new SingleThreadedSchedulingExecutor("shutdown", "shutdown", 100, exceptionHandler, registry)) {
            CountDownLatch release = occupy(meteredExecutor);
            var followUpScheduled = new CompletableFuture<Boolean>();
            // Queued behind the blocker, so it runs while close() is draining; re-arming a timer then reaches the live pool.
            meteredExecutor.execute(() -> {
                meteredExecutor.schedule(Duration.ofHours(1), () -> {});
                followUpScheduled.complete(true);
            });

            closeWhileDraining(meteredExecutor, release);

            assertThat(followUpScheduled).as("the follow-up task ran and rescheduled during the drain").succeedsWithin(Duration.ofSeconds(10)).isEqualTo(true);
            assertThat(rejections).as("no task is rejected during the drain").isEmpty();
        }
    }

    @Test
    void drainsSelfFedImmediateBacklogOnClose() {
        var counter = new AtomicInteger();
        int chainLength = 5;
        try (var selfFeedingExecutor = new SingleThreadedSchedulingExecutor("selffeed", exceptionHandler)) {
            CountDownLatch release = occupy(selfFeedingExecutor);
            // Each link runs during the drain and enqueues the next, so close() keeps draining until the chain is exhausted.
            var chain = new Runnable() {
                @Override
                public void run() {
                    if (counter.incrementAndGet() < chainLength) {
                        selfFeedingExecutor.execute(this);
                    }
                }
            };
            selfFeedingExecutor.execute(chain);

            closeWhileDraining(selfFeedingExecutor, release);

            assertThat(counter).as("every self-fed follow-up ran before shutdown").hasValue(chainLength);
        }
    }

    @Test
    void doesNotAwaitTimersRearmedDuringShutdown() {
        try (var timerExecutor = new SingleThreadedSchedulingExecutor("timers", exceptionHandler)) {
            CountDownLatch release = occupy(timerExecutor);
            timerExecutor.execute(() -> timerExecutor.schedule(Duration.ofHours(1), () -> {}));

            Duration closeElapsed = closeWhileDraining(timerExecutor, release);

            assertThat(closeElapsed).as("close returns as soon as the immediate work is done").isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test
    void forcesShutdownWhenBacklogDoesNotDrainWithinTimeout() {
        var shortTimeout = Duration.ofMillis(300);
        try (var stuckExecutor = new SingleThreadedSchedulingExecutor("stuck", "stuck", 100, exceptionHandler, null, shortTimeout)) {
            CountDownLatch release = occupy(stuckExecutor);
            stuckExecutor.execute(() -> {});   // an immediate task that cannot run while the single thread is wedged
            // The backlog cannot drain within the timeout, so close() gives up and forces shutdown; the drain and termination share one budget.
            long startNanoTime = System.nanoTime();
            stuckExecutor.close();
            var closeElapsed = Duration.ofNanos(System.nanoTime() - startNanoTime);
            release.countDown();

            assertThat(closeElapsed).as("total teardown is bounded by one shutdown timeout")
                                    .isLessThan(shortTimeout.multipliedBy(3).dividedBy(2));
        }
    }

    @Test
    void abandonsDrainWhenShutdownThreadIsInterrupted() {
        try (var stuckExecutor = new SingleThreadedSchedulingExecutor("interrupt", "interrupt", 100, exceptionHandler, null, Duration.ofMinutes(1))) {
            CountDownLatch release = occupy(stuckExecutor);
            stuckExecutor.execute(() -> {});   // keeps the drain waiting on the wedged thread for the full one-minute timeout
            var closed = new CompletableFuture<Void>();
            var closeThread = new Thread(() -> {
                stuckExecutor.close();
                closed.complete(null);
            }, "interrupt-close");
            closeThread.start();
            awaitBlocked(closeThread);
            closeThread.interrupt();

            assertThat(closed).as("an interrupt abandons the drain so close returns well within the one-minute timeout").succeedsWithin(Duration.ofSeconds(10));
            release.countDown();
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

    /// Occupies the executor's single thread until the returned latch is counted down, so any task submitted afterwards queues behind it.
    private static CountDownLatch occupy(SingleThreadedSchedulingExecutor executor) {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.execute(() -> {
            running.countDown();
            awaitUninterruptibly(release);
        });
        awaitUninterruptibly(running);
        return release;
    }

    /// Runs [SingleThreadedSchedulingExecutor#close()] on another thread, waits until it is blocked inside its drain, then releases the occupying blocker
    /// so the backlog runs into the live pool.
    ///
    /// @return how long close took, measured from the blocker's release
    private static Duration closeWhileDraining(SingleThreadedSchedulingExecutor executor, CountDownLatch releaseBlocker) {
        var closed = new CompletableFuture<Void>();
        var closeThread = new Thread(() -> {
            executor.close();
            closed.complete(null);
        }, "test-close");
        closeThread.start();
        awaitBlocked(closeThread);
        long releaseNanoTime = System.nanoTime();
        releaseBlocker.countDown();
        assertThat(closed).as("close completes once the backlog drains").succeedsWithin(Duration.ofSeconds(30));
        return Duration.ofNanos(System.nanoTime() - releaseNanoTime);
    }

    private static void awaitBlocked(Thread thread) {
        long deadlineNanoTime = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadlineNanoTime) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread '" + thread.getName() + "' did not reach a waiting state, was " + thread.getState());
    }
}
