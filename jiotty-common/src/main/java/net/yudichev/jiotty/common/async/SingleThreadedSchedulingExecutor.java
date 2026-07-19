package net.yudichev.jiotty.common.async;

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
import static java.util.concurrent.TimeUnit.SECONDS;

/// A single-threaded [SchedulingExecutor] backed by a one-thread [ScheduledThreadPoolExecutor]. The queue of pending immediate tasks (`execute`/`submit`,
/// **not** scheduled) is bounded: a submit beyond `maxQueueSize` is rejected with [RejectedExecutionException] rather than piling up unbounded and
/// pushing the shared JVM toward OOM while the single thread is blocked. Scheduled/periodic tasks (`schedule*`) do not count against the bound.
///
/// When a [MeterRegistry] is supplied, the standard [ExecutorServiceMetrics] meters (queue depth, active, pool, execution + queue-wait timers, completed)
/// plus a custom `executor.queued.immediate` gauge and `executor.rejected` counter are published tagged `name`/`family`, and removed when the executor closes.
/// (Micrometer's dot-separated names expose to Prometheus with underscores — e.g. `executor.queued.immediate` → `executor_queued_immediate`.)
public final class SingleThreadedSchedulingExecutor implements SchedulingExecutor, StringFormattable {
    private static final Logger logger = LogManager.getLogger(SingleThreadedSchedulingExecutor.class);

    private final Set<Closeable> scheduleHandles = Sets.newConcurrentHashSet();
    /// Pending immediate — `execute`/`submit`, not scheduled — tasks not yet started; the value the bound is enforced against. Scheduled/periodic tasks share
    /// the underlying JDK queue but are deliberately excluded, so a handful of long-lived periodic schedules never eat into the immediate-task headroom.
    private final AtomicInteger pendingImmediateTasks = new AtomicInteger();
    private final ScheduledExecutorService executor;
    private final int maxQueueSize;
    private final String threadNameBase;
    private final BiConsumer<String, Throwable> taskExceptionHandler;
    @Nullable
    private final Counter rejectedCounter;
    private final Runnable meterCleanup;

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
        checkArgument(maxQueueSize > 0, "maxQueueSize must be positive: %s", maxQueueSize);
        threadNameBase = checkNotNull(name, "name");
        this.maxQueueSize = maxQueueSize;
        taskExceptionHandler = checkNotNull(exceptionHandler, "exceptionHandler")::onTaskException;
        var delegatePool = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder()
                .setNameFormat(name + "-%s")
                .setDaemon(true)
                .build());
        // Evict a cancelled scheduled task from the queue at cancel time instead of holding it (and its captured graph) until its fire time — matters for
        //  cancel-and-reschedule patterns like debounce. Set on the concrete pool because the ExecutorServiceMetrics wrapper does not expose this setter.
        delegatePool.setRemoveOnCancelPolicy(true);
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
        executor.execute(() -> runImmediate(() -> {
            try {
                resultFuture.complete(task.call());
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        }));
        return resultFuture;
    }

    @Override
    public void execute(Runnable command) {
        reserveImmediateSlot();
        executor.execute(() -> runImmediate(command));
    }

    @Override
    public void executeAndAwaitIfLive(Runnable command, Duration timeout) {
        reserveImmediateSlot();
        try {
            executor.submit(() -> runImmediate(command)).get(timeout.toNanos(), NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
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
    public void close() {
        Closeable.forCloseables(scheduleHandles).close();
        logger.info("Shutting down {}", threadNameBase);
        if (MoreExecutors.shutdownAndAwaitTermination(executor, 10, SECONDS)) {
            logger.info("Shut down {}", threadNameBase);
        } else {
            logger.warn("Was not able to gracefully stop executor '{}' in 10 seconds", threadNameBase);
        }
        meterCleanup.run();
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
    private void runImmediate(Runnable command) {
        pendingImmediateTasks.decrementAndGet();
        Runnables.runGuarded(logger, "task", command, taskExceptionHandler);
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
