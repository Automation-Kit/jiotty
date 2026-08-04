package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

final class DeterministicExecutor extends BaseIdempotentCloseable implements SchedulingExecutor {
    private final ProgrammableClock clock;
    private final String threadNameBase;
    private boolean closed;

    DeterministicExecutor(ProgrammableClock clock, String threadNameBase) {
        this.clock = checkNotNull(clock);
        this.threadNameBase = checkNotNull(threadNameBase);
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
        checkNotClosed();
        var resultFuture = new CompletableFuture<T>();
        clock.executeImmediate(this, () -> {
            try {
                resultFuture.complete(task.call());
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        });
        return resultFuture;
    }

    @Override
    public Closeable schedule(Duration delay, Runnable command) {
        checkNotClosed();
        return clock.schedule(this, delay, command);
    }

    @Override
    public Closeable scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable command) {
        checkNotClosed();
        return clock.scheduleAtFixedRate(this, initialDelay, period, command);
    }

    /// Drains this executor's immediate backlog before marking it closed, so work submitted to it during shutdown runs here exactly as it does on a live
    /// executor. The executor is open for the duration of the drain, so a task may submit follow-up work.
    @Override
    public void doClose() {
        clock.closeExecutor(this);
        closed = true;
    }

    String getThreadNameBase() {
        return threadNameBase;
    }

    private void checkNotClosed() {
        checkState(!closed, "closed");
    }
}
