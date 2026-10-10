package net.yudichev.jiotty.common.async;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleThreadedSchedulingExecutorTest {
    private static final Logger logger = LogManager.getLogger(SingleThreadedSchedulingExecutorTest.class);

    private ListenerBackedTaskExceptionHandlerRegistry exceptionHandler;
    private SingleThreadedSchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        exceptionHandler = new ListenerBackedTaskExceptionHandlerRegistry();
        executor = executorNamed("test");
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
        try (var selfFeedingExecutor = executorNamed("selffeed")) {
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
        try (var timerExecutor = executorNamed("timers")) {
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

    @Test
    void tryExecuteRunsTheTaskWhileTheExecutorIsLive() {
        var ran = new CompletableFuture<Void>();

        boolean queued = executor.tryExecute("test", () -> ran.complete(null));

        assertThat(queued).isTrue();
        assertThat(ran).succeedsWithin(Duration.ofSeconds(10));
    }

    @Test
    void tryExecuteDiscardsTheTaskOnceTheExecutorIsClosed() {
        var ran = new CompletableFuture<Void>();
        executor.close();

        boolean queued = executor.tryExecute("test", () -> ran.complete(null));

        assertThat(queued).as("a closed executor reports it cannot take the task").isFalse();
        assertThat(ran).as("a discarded task never runs").isNotDone();
    }

    /// Only a shut-down executor makes `tryExecute` return `false`; a full queue rejects the task as `execute` does, counted by the counter the alert watches.
    @Test
    void tryExecuteThrowsAndCountsTheRejectionWhenTheQueueIsFull() {
        var registry = new SimpleMeterRegistry();
        try (var boundedExecutor = new SingleThreadedSchedulingExecutor("bounded", "fam", 1, exceptionHandler, registry)) {
            CountDownLatch release = occupy(boundedExecutor);
            boundedExecutor.execute(() -> {});   // fills the single queue slot behind the occupying task

            assertThatThrownBy(() -> boundedExecutor.tryExecute("test", () -> {})).isInstanceOf(RejectedExecutionException.class);

            assertThat(registry.get("executor.rejected").tags("name", "bounded", "reason", "queue_full").counter().count()).isEqualTo(1.0);
            assertThat(registry.get("executor.queued.immediate").tags("name", "bounded").gauge().value())
                    .as("the rejected task released the slot it reserved")
                    .isEqualTo(1.0);
            release.countDown();
        }
    }

    @Test
    void aFullQueuePanicsTheOwnerTheExecutorWasCreatedUnder() {
        var owner = new RecordingOwner();
        try (SingleThreadedSchedulingExecutor ownedExecutor = ownedExecutor(owner, "owned", Duration.ofSeconds(10))) {
            CountDownLatch release = occupy(ownedExecutor);
            ownedExecutor.execute("queued", () -> {});

            assertThatThrownBy(() -> ownedExecutor.execute("rejected", () -> {}))
                    .isInstanceOfSatisfying(QueueFullException.class, rejection -> assertThat(rejection.ownerPanicked()).isTrue());
            assertThat(owner.panickedExecutorNames).containsExactly("owned");
            release.countDown();
        }
    }

    @Test
    void aFullQueueOfAnExecutorCreatedOutsideAnyOwnerPanicsNothing() {
        try (var unownedExecutor = new SingleThreadedSchedulingExecutor("unowned", "unowned", 1, exceptionHandler, null)) {
            CountDownLatch release = occupy(unownedExecutor);
            unownedExecutor.execute("queued", () -> {});

            assertThatThrownBy(() -> unownedExecutor.execute("rejected", () -> {}))
                    .isInstanceOfSatisfying(QueueFullException.class, rejection -> assertThat(rejection.ownerPanicked()).isFalse());
            release.countDown();
        }
    }

    /// The panic leaves the queue empty for the owner's teardown, and the tasks queued before it never run.
    @Test
    void aDiscardSkipsTheTasksQueuedBeforeItAndRunsThoseQueuedAfter() {
        var owner = new RecordingOwner();
        try (SingleThreadedSchedulingExecutor ownedExecutor = ownedExecutor(owner, "owned", 2, Duration.ofSeconds(10))) {
            CountDownLatch release = occupy(ownedExecutor);
            var discardedTaskRan = new AtomicBoolean();
            ownedExecutor.execute("discarded", () -> discardedTaskRan.set(true));

            owner.discardBacklogs();
            var teardownRan = new CompletableFuture<Void>();
            ownedExecutor.execute("teardown", () -> teardownRan.complete(null));
            release.countDown();

            assertThat(teardownRan).succeedsWithin(Duration.ofSeconds(10));
            assertThat(discardedTaskRan).isFalse();
        }
    }

    @Test
    void aDiscardedSubmitLeavesItsFutureIncomplete() {
        var owner = new RecordingOwner();
        try (SingleThreadedSchedulingExecutor ownedExecutor = ownedExecutor(owner, "owned", 2, Duration.ofSeconds(10))) {
            CountDownLatch release = occupy(ownedExecutor);
            CompletableFuture<Integer> discardedResult = ownedExecutor.submit(() -> 1);

            owner.discardBacklogs();
            var afterDiscardRan = new CompletableFuture<Void>();
            ownedExecutor.execute("after discard", () -> afterDiscardRan.complete(null));
            release.countDown();

            assertThat(afterDiscardRan).succeedsWithin(Duration.ofSeconds(10));
            assertThat(discardedResult).isNotDone();
        }
    }

    /// A discarded task's slot is held until the executor's thread reaches and skips it, and released then like any other.
    @Test
    void aDiscardedTaskReleasesItsSlotOnceSkipped() {
        var owner = new RecordingOwner();
        try (SingleThreadedSchedulingExecutor ownedExecutor = ownedExecutor(owner, "owned", Duration.ofSeconds(10))) {
            CountDownLatch release = occupy(ownedExecutor);
            ownedExecutor.execute("discarded", () -> {});
            owner.discardBacklogs();
            assertThatThrownBy(() -> ownedExecutor.execute("rejected", () -> {})).isInstanceOf(QueueFullException.class);
            // Scheduled, so it takes no slot, and it runs once the skipped task ahead of it has released its own.
            var skippedTaskPassed = new CompletableFuture<Void>();
            ownedExecutor.schedule(Duration.ZERO, () -> skippedTaskPassed.complete(null));
            release.countDown();
            assertThat(skippedTaskPassed).succeedsWithin(Duration.ofSeconds(10));

            var ran = new CompletableFuture<Void>();
            ownedExecutor.execute("after the skipped task", () -> ran.complete(null));

            assertThat(ran).succeedsWithin(Duration.ofSeconds(10));
        }
    }

    /// A rejection after close comes from a producer that outlived its consumer, not from work the owner has fallen behind on.
    @Test
    void aClosedExecutorPanicsNoOwner() {
        var owner = new RecordingOwner();
        SingleThreadedSchedulingExecutor stuckExecutor = ownedExecutor(owner, "stuck", Duration.ofMillis(300));
        CountDownLatch release = occupy(stuckExecutor);
        stuckExecutor.execute("stranded", () -> {});
        // The backlog cannot drain, so the close is forced and leaves the stranded task counted against the bound.
        stuckExecutor.close();

        assertThatThrownBy(() -> stuckExecutor.execute("late", () -> {}))
                .isInstanceOfSatisfying(QueueFullException.class, rejection -> assertThat(rejection.ownerPanicked()).isFalse());
        assertThat(owner.panickedExecutorNames).isEmpty();
        release.countDown();
    }

    @Test
    void anOwnerFailingToPanicIsReportedAndTheRejectionStillThrown() {
        var reportedTaskName = new CompletableFuture<String>();
        exceptionHandler.addExceptionHandler((taskName, _) -> reportedTaskName.complete(taskName));
        ExecutorOwner failingOwner = new RecordingOwner() {
            @Override
            public void onQueueFull(String executorName, QueueFullException rejection) {
                throw new IllegalStateException("cannot panic");
            }
        };
        try (SingleThreadedSchedulingExecutor ownedExecutor = ownedExecutor(failingOwner, "owned", Duration.ofSeconds(10))) {
            CountDownLatch release = occupy(ownedExecutor);
            ownedExecutor.execute("queued", () -> {});

            assertThatThrownBy(() -> ownedExecutor.execute("rejected", () -> {}))
                    .isInstanceOfSatisfying(QueueFullException.class, rejection -> assertThat(rejection.ownerPanicked()).isFalse());
            assertThat(reportedTaskName).succeedsWithin(Duration.ofSeconds(10)).isEqualTo("panicking the owner of owned");
            release.countDown();
        }
    }

    @Test
    void aFiredOneShotScheduleReleasesItsHandle() {
        var ran = new CompletableFuture<Void>();

        executor.schedule(Duration.ZERO, () -> ran.complete(null));

        assertThat(ran).succeedsWithin(Duration.ofSeconds(10));
        assertThat(executor.scheduledHandleCount()).isZero();
    }

    @Test
    void aClosedPeriodicScheduleReleasesItsHandle() {
        var firstRun = new CompletableFuture<Void>();
        Closeable schedule = executor.scheduleAtFixedRate(Duration.ZERO, Duration.ofHours(1), () -> firstRun.complete(null));
        assertThat(firstRun).succeedsWithin(Duration.ofSeconds(10));

        schedule.close();

        assertThat(executor.scheduledHandleCount()).isZero();
    }

    @Test
    void aClosedScheduleReleasesItsHandle() {
        Closeable schedule = executor.schedule(Duration.ofHours(1), () -> {});

        schedule.close();

        assertThat(executor.scheduledHandleCount()).isZero();
    }

    @Test
    void aScheduleRejectedByAShutDownExecutorLeavesNoHandle() {
        executor.close();

        assertThatThrownBy(() -> executor.schedule(Duration.ofHours(1), () -> {})).isInstanceOf(RejectedExecutionException.class);
        assertThat(executor.scheduledHandleCount()).isZero();
    }

    @Test
    void aScheduleWhoseHandleCloseRacesAheadOfItsFutureNeverRuns() {
        var pool = new PausingPool();
        var raceExecutor = new SingleThreadedSchedulingExecutor("race", "race", 100, exceptionHandler, null, Duration.ofSeconds(10), pool);
        CountDownLatch releaseBlocker = occupy(raceExecutor);
        var ran = new AtomicBoolean();
        var schedulingThread = new Thread(() -> raceExecutor.schedule(Duration.ZERO, () -> ran.set(true)), "test-schedule");
        pool.pauseSchedulingOn(schedulingThread);
        schedulingThread.start();
        await(pool.scheduled, "the task was queued and its future not yet handed back");

        var closeThread = new Thread(raceExecutor::close, "test-close");
        closeThread.start();
        // close() closes every handle before it parks in its drain behind the blocker, so the handle is closed while its future is still unset.
        awaitBlocked(closeThread);
        pool.resumeScheduling.countDown();
        join(schedulingThread);
        releaseBlocker.countDown();
        join(closeThread);

        assertThat(ran).isFalse();
    }

    private SingleThreadedSchedulingExecutor executorNamed(String name) {
        return new SingleThreadedSchedulingExecutor(name, name, ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE, exceptionHandler, null);
    }

    /// An executor bounded at one queued task, constructed while `owner` is bound.
    private SingleThreadedSchedulingExecutor ownedExecutor(ExecutorOwner owner, String name, Duration shutdownTimeout) {
        return ownedExecutor(owner, name, 1, shutdownTimeout);
    }

    private SingleThreadedSchedulingExecutor ownedExecutor(ExecutorOwner owner, String name, int maxQueueSize, Duration shutdownTimeout) {
        return ScopedValue.where(ExecutorOwner.CURRENT, owner)
                          .call(() -> new SingleThreadedSchedulingExecutor(name, name, maxQueueSize, exceptionHandler, null, shutdownTimeout));
    }

    /// Occupies the executor's single thread until the returned latch is counted down, so any task submitted afterwards queues behind it.
    private static CountDownLatch occupy(SingleThreadedSchedulingExecutor executor) {
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        executor.execute(() -> {
            running.countDown();
            await(release, "the occupying task was released");
        });
        await(running, "the occupying task started");
        return release;
    }

    /// Bounded and interruptible, so a latch that never opens fails the test instead of hanging the build with no way to kill it.
    private static void await(CountDownLatch latch, String what) {
        assertThat(getAsUnchecked(() -> latch.await(10, SECONDS))).as(what).isTrue();
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

    private static void join(Thread thread) {
        assertThat(getAsUnchecked(() -> thread.join(Duration.ofSeconds(10)))).as("thread '%s' finished", thread.getName()).isTrue();
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

    /// Records the names of the executors that report a full queue, and discards the owned executors' backlogs when told to.
    private static class RecordingOwner implements ExecutorOwner {
        final List<String> panickedExecutorNames = new CopyOnWriteArrayList<>();
        private final List<Runnable> backlogDiscarders = new CopyOnWriteArrayList<>();

        @Override
        public void addBacklogDiscarder(Runnable backlogDiscarder) {
            backlogDiscarders.add(backlogDiscarder);
        }

        @Override
        public void onQueueFull(String executorName, QueueFullException rejection) {
            rejection.markOwnerPanicked();
            panickedExecutorNames.add(executorName);
        }

        /// Discards the backlogs of the owned executors, as a panic of the owner does.
        void discardBacklogs() {
            backlogDiscarders.forEach(Runnable::run);
        }
    }

    /// Holds one thread's [#schedule(Runnable, long, TimeUnit)] call after the task is queued and before its future is returned, which is the window the
    /// handle's guard covers.
    private static final class PausingPool extends ScheduledThreadPoolExecutor {
        final CountDownLatch scheduled = new CountDownLatch(1);
        final CountDownLatch resumeScheduling = new CountDownLatch(1);
        /// The thread whose scheduling is held; `null` holds none.
        private volatile @Nullable Thread pausedThread;

        PausingPool() {
            super(1, runnable -> {
                var thread = new Thread(runnable, "race-pool");
                thread.setDaemon(true);
                return thread;
            });
        }

        void pauseSchedulingOn(Thread thread) {
            pausedThread = thread;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            ScheduledFuture<?> future = super.schedule(command, delay, unit);
            if (Thread.currentThread() == pausedThread) {
                scheduled.countDown();
                await(resumeScheduling, "the scheduling thread was resumed");
            }
            return future;
        }
    }
}
