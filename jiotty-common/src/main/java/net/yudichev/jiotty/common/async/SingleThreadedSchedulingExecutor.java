package net.yudichev.jiotty.common.async;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Runnables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class SingleThreadedSchedulingExecutor implements SchedulingExecutor {
    private static final Logger logger = LogManager.getLogger(SingleThreadedSchedulingExecutor.class);

    private final Set<Closeable> scheduleHandles = Sets.newConcurrentHashSet();
    private final ScheduledExecutorService executor;
    private final String threadNameBase;

    @Inject
    public SingleThreadedSchedulingExecutor(@Assisted String threadNameBase) {
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                                                                      .setNameFormat(threadNameBase + "-%s")
                                                                      .setDaemon(true)
                                                                      .build());
        this.threadNameBase = threadNameBase;
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
        var resultFuture = new CompletableFuture<T>();
        executor.execute(guard("task", () -> {
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
        executor.execute(guard("task", command));
    }

    @Override
    public void executeAndAwaitIfLive(Runnable command, Duration timeout) {
        try {
            executor.submit(guard("task", command)).get(timeout.toNanos(), NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Closeable schedule(Duration delay, Runnable command) {
        Closeable scheduledHandle = new ScheduledHandle(executor.schedule(
                guard("scheduled task", command), delay.toNanos(), NANOSECONDS));
        scheduleHandles.add(scheduledHandle);
        return scheduledHandle;
    }

    @Override
    public Closeable scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable command) {
        Closeable scheduledHandle = new ScheduledHandle(
                executor.scheduleAtFixedRate(guard("scheduled task", command), initialDelay.toNanos(), period.toNanos(), NANOSECONDS));
        scheduleHandles.add(scheduledHandle);
        return scheduledHandle;
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
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + '-' + threadNameBase;
    }

    private static Runnable guard(String task, Runnable command) {
        return Runnables.guarded(logger, task, command);
    }

    private final class ScheduledHandle extends BaseIdempotentCloseable {
        private final Future<?> scheduledFuture;
        private final Closeable executorHandle;

        private ScheduledHandle(Future<?> scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
            executorHandle = () -> scheduledFuture.cancel(false);
        }

        @Override
        protected void doClose() {
            executorHandle.close();
            scheduleHandles.remove(this);
        }

        @Override
        public String toString() {
            return scheduledFuture.toString();
        }
    }
}
