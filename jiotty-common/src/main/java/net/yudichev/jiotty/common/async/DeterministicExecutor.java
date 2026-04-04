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
        var resultFuture = new CompletableFuture<T>();
        schedule(Duration.ZERO, () -> {
            try {
                resultFuture.complete(task.call());
            } catch (Exception e) {
                resultFuture.completeExceptionally(e);
            }
        });
        return resultFuture;
    }

    @Override
    public void executeAndAwaitIfLive(Runnable command, Duration timeout) {
        execute(command);
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
