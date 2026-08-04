package net.yudichev.jiotty.common.async;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Runnables;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// A single-threaded [SchedulingExecutor] backed by a one-thread [ScheduledThreadPoolExecutor]. The queue of pending immediate tasks (`execute`/`submit`,
/// **not** scheduled) is bounded: a submit beyond `maxQueueSize` is rejected with [RejectedExecutionException] rather than piling up unbounded and
/// pushing the shared JVM toward OOM while the single thread is blocked. Scheduled/periodic tasks (`schedule*`) do not count against the bound.
///
/// When a [MeterRegistry] is supplied, the standard [ExecutorServiceMetrics] meters (queue depth, active, pool, execution + queue-wait timers, completed)
/// plus a custom `executor.queued.immediate` gauge and `executor.rejected` counter are published tagged `name`/`family`, and removed when the executor closes.
/// (Micrometer's dot-separated names expose to Prometheus with underscores — e.g. `executor.queued.immediate` → `executor_queued_immediate`.)
///
/// [#close()] keeps the pool live while it runs the immediate-task backlog — and any immediate follow-ups those tasks submit — to completion, then shuts the
/// pool down, cancelling any future-dated scheduled/periodic task. A task that reschedules itself mid-drain (e.g. a throttle re-arming its timer) runs on the
/// live pool; a fixed deadline bounds a self-feeding backlog.
public final class SingleThreadedSchedulingExecutor extends BaseIdempotentCloseable implements SchedulingExecutor, StringFormattable {
    private static final Logger logger = LogManager.getLogger(SingleThreadedSchedulingExecutor.class);
    /// Bounds both the immediate-backlog drain and the pool termination in [#close()], so a wedged task cannot hold shutdown open indefinitely.
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final Set<Closeable> scheduleHandles = Sets.newConcurrentHashSet();
    /// Pending immediate — `execute`/`submit`, not scheduled — tasks not yet started; the value the bound is enforced against. Scheduled/periodic tasks share
    /// the underlying JDK queue but are deliberately excluded, so a handful of long-lived periodic schedules never eat into the immediate-task headroom.
    private final AtomicInteger pendingImmediateTasks = new AtomicInteger();
    /// The submission view: the metering wrapper when a registry is supplied, otherwise [#delegatePool] itself.
    private final ScheduledExecutorService executor;
    /// The concrete pool. [#close()] submits the drain barrier and terminates the pool through it.
    private final ScheduledThreadPoolExecutor delegatePool;
    private final int maxQueueSize;
    private final String threadNameBase;
    private final BiConsumer<String, Throwable> taskExceptionHandler;
    @Nullable
    private final Counter rejectedCounter;
    private final Runnable meterCleanup;
    private final Duration shutdownTimeout;

    public SingleThreadedSchedulingExecutor(String threadNameBase) {
        this(threadNameBase, threadNameBase, ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE, new ListenerBackedTaskExceptionHandlerRegistry(), null);
    }

    SingleThreadedSchedulingExecutor(String threadNameBase, ListenerBackedTaskExceptionHandlerRegistry exceptionHandler) {
        this(threadNameBase, threadNameBase, ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE, exceptionHandler, null);
    }

    /// Matches [ExecutorFactory#createSingleThreadedSchedulingExecutor(String, String, int)] so a `SingleThreadedSchedulingExecutor::new` reference is itself
    /// an unmetered [ExecutorFactory]; each executor gets its own exception-handler registry.
    public SingleThreadedSchedulingExecutor(String name, String family, int maxQueueSize) {
        this(name, family, maxQueueSize, new ListenerBackedTaskExceptionHandlerRegistry(), null);
    }

    SingleThreadedSchedulingExecutor(String name,
                                     String family,
                                     int maxQueueSize,
                                     ListenerBackedTaskExceptionHandlerRegistry exceptionHandler,
                                     @Nullable MeterRegistry meterRegistry) {
        this(name, family, maxQueueSize, exceptionHandler, meterRegistry, SHUTDOWN_TIMEOUT);
    }

    @VisibleForTesting
    SingleThreadedSchedulingExecutor(String name,
                                     String family,
                                     int maxQueueSize,
                                     ListenerBackedTaskExceptionHandlerRegistry exceptionHandler,
                                     @Nullable MeterRegistry meterRegistry,
                                     Duration shutdownTimeout) {
        checkArgument(maxQueueSize > 0, "maxQueueSize must be positive: %s", maxQueueSize);
        threadNameBase = checkNotNull(name, "name");
        this.maxQueueSize = maxQueueSize;
        this.shutdownTimeout = checkNotNull(shutdownTimeout, "shutdownTimeout");
        taskExceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler")::onTaskException;
        delegatePool = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder()
                .setNameFormat(name + "-%s")
                .setDaemon(true)
                .build());
        // Evict a cancelled scheduled task from the queue at cancel time instead of holding it (and its captured graph) until its fire time — matters for
        //  cancel-and-reschedule patterns like debounce. Set on the concrete pool because the ExecutorServiceMetrics wrapper does not expose these setters.
        delegatePool.setRemoveOnCancelPolicy(true);
        // Shutdown discards any pending delayed task, so awaitTermination completes once the immediate work is done.
        delegatePool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        if (meterRegistry == null) {
            executor = delegatePool;
            rejectedCounter = null;
            meterCleanup = () -> {};
        } else {
            var tags = Tags.of("family", family);
            // monitor(...) both wraps the executor to time each task (execution + queue-wait) and registers the queue/active/pool/completed gauges; it tags
            //  every meter with name=<name> plus these tags. The unique per-instance name lets the same-family gauges coexist without collision.
            executor = ExecutorServiceMetrics.monitor(meterRegistry, delegatePool, name, tags);
            rejectedCounter = meterRegistry.counter("executor.rejected", Tags.of("name", name, "family", family, "reason", "queue_full"));
            // executor.queued (from monitor) is total queue depth; this gauge is the immediate-task (not scheduled) count the bound is enforced against.
            Gauge.builder("executor.queued.immediate", pendingImmediateTasks, AtomicInteger::get)
                 .tags("name", name, "family", family)
                 .register(meterRegistry);
            meterCleanup = () -> removeMeters(meterRegistry, name, family);
        }
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
        reserveImmediateSlot();
        var resultFuture = new CompletableFuture<T>();
        executor.execute(() -> runImmediate("task", () -> {
            try {
                resultFuture.complete(task.call());
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        }));
        return resultFuture;
    }

    @Override
    public void execute(String taskName, Runnable command) {
        reserveImmediateSlot();
        executor.execute(() -> runImmediate(taskName, command));
    }

    @Override
    public Closeable schedule(Duration delay, Runnable command) {
        return register(executor.schedule(guard("scheduled task", command), delay.toNanos(), NANOSECONDS));
    }

    @Override
    public Closeable scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable command) {
        return register(executor.scheduleAtFixedRate(guard("scheduled task", command), initialDelay.toNanos(), period.toNanos(), NANOSECONDS));
    }

    @Override
    public void doClose() {
        // The drain and the pool termination share one deadline, so the total time spent here is bounded by shutdownTimeout.
        long deadlineNanoTime = System.nanoTime() + shutdownTimeout.toNanos();
        Closeable.closeSafelyIfNotNull(logger, scheduleHandles);
        logger.info("Shutting down {}", threadNameBase);
        if (!quiesceImmediateBacklog(deadlineNanoTime)) {
            logger.warn("Executor '{}' did not drain within {}; {} immediate task(s) still pending, forcing shutdown",
                        threadNameBase, shutdownTimeout, pendingImmediateTasks.get());
        }
        if (MoreExecutors.shutdownAndAwaitTermination(delegatePool, Math.max(0, deadlineNanoTime - System.nanoTime()), NANOSECONDS)) {
            logger.info("Shut down {}", threadNameBase);
        } else {
            logger.warn("Was not able to gracefully stop executor '{}' within {}", threadNameBase, shutdownTimeout);
        }
        meterCleanup.run();
    }

    /// Runs the immediate-task backlog to completion on the pool's single thread while the pool is live, so a task that submits more immediate work — or
    /// reschedules itself — during teardown runs on it. Each iteration posts a probe that runs after the current backlog and reads [#pendingImmediateTasks]
    /// **on the pool thread**, where no task is mid-flight, so the count is exactly the tasks still queued (every follow-up an already-run task enqueued is
    /// included). A non-zero count means more work arrived behind the probe, so it loops; zero means quiescent. Delayed tasks never count against
    /// [#pendingImmediateTasks], so a re-armed timer leaves the drain free to finish.
    ///
    /// @param deadlineNanoTime the [System#nanoTime()] value past which the drain gives up on a self-feeding backlog
    /// @return `true` once the backlog is empty, `false` if the deadline passed first
    private boolean quiesceImmediateBacklog(long deadlineNanoTime) {
        while (true) {
            // The probe reads the count on the pool thread, where no task is mid-flight, so it sees the true queued backlog. Submitting it straight to the
            // concrete pool keeps it off the immediate-task bound and the metered timer.
            Future<Integer> remainingImmediateTasks = delegatePool.submit(pendingImmediateTasks::get);
            try {
                // A non-positive remaining budget throws TimeoutException at once, so the deadline is the loop's exit even across many drain rounds.
                if (remainingImmediateTasks.get(deadlineNanoTime - System.nanoTime(), NANOSECONDS) == 0) {
                    return true;
                }
            } catch (InterruptedException e) {
                // An interrupt on the shutdown thread means "stop now": abandon the drain and let the caller force termination.
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException e) {
                return false;
            }
        }
    }

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, getClass().getSimpleName());
        Append.to(appendable, '-');
        Append.to(appendable, threadNameBase);
    }

    /// Reserves a slot against the immediate-task (not scheduled) bound — rejecting loudly (so a wedged thread cannot silently grow the heap toward OOM) when
    /// full. Called before enqueue; the slot is released by [#runImmediate] when the task starts. Only immediate `execute`/`submit` tasks are counted;
    /// scheduled/periodic tasks share the underlying JDK queue but must not consume the bound.
    private void reserveImmediateSlot() {
        // Unconditional getAndIncrement (a single LSE atomic on arm64, no CAS-retry loop), backed out on the reject path. The count can transiently overshoot
        //  maxQueueSize by the number of racing producers before they back out, but acceptance stays hard-bounded: a producer proceeds only when its
        //  pre-increment value was below the bound, so at most maxQueueSize tasks are ever accepted concurrently.
        if (pendingImmediateTasks.getAndIncrement() >= maxQueueSize) {
            pendingImmediateTasks.getAndDecrement();
            if (rejectedCounter != null) {
                rejectedCounter.increment();
            }
            throw new RejectedExecutionException("Executor '" + threadNameBase + "' rejected a task: queue is full (" + maxQueueSize + ')');
        }
    }

    /// Runs one reserved immediate task on the executor thread: releases its bound slot, then runs `command` under the exception guard — applied
    /// allocation-free via [Runnables#runGuarded], so an immediate submit wraps `command` in a single object rather than a guard wrapper plus a slot wrapper.
    private void runImmediate(String taskName, Runnable command) {
        pendingImmediateTasks.decrementAndGet();
        Runnables.runGuarded(logger, taskName, command, taskExceptionHandler);
    }

    private static void removeMeters(MeterRegistry meterRegistry, String name, String family) {
        meterRegistry.getMeters().stream()
                     .filter(meter -> name.equals(meter.getId().getTag("name")) && family.equals(meter.getId().getTag("family")))
                     .forEach(meterRegistry::remove);
    }

    private Runnable guard(String task, Runnable command) {
        return Runnables.guarded(logger, task, command, taskExceptionHandler);
    }

    private Closeable register(Future<?> scheduledFuture) {
        var handle = new ScheduledHandle(scheduledFuture);
        scheduleHandles.add(handle);
        return handle;
    }

    private final class ScheduledHandle extends BaseIdempotentCloseable {
        private final Future<?> scheduledFuture;

        private ScheduledHandle(Future<?> scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        @Override
        protected void doClose() {
            scheduledFuture.cancel(false);
            scheduleHandles.remove(this);
        }

        @Override
        public String toString() {
            return scheduledFuture.toString();
        }
    }
}
