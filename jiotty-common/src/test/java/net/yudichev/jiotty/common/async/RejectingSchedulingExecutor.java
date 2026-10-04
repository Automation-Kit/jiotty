package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkNotNull;

/// Wraps an executor to reject tasks the way a live [SingleThreadedSchedulingExecutor] does when its queue is full or once it has shut down. Until told to
/// reject, it passes everything through; once closed, it closes the wrapped executor and rejects every task.
public final class RejectingSchedulingExecutor extends BaseIdempotentCloseable implements SchedulingExecutor {
    private final SchedulingExecutor delegate;
    private boolean queueFull;

    public RejectingSchedulingExecutor(SchedulingExecutor delegate) {
        this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    protected void doClose() {
        delegate.close();
    }

    /// Rejects immediate tasks from now on, as an executor whose queue is full does; delayed tasks are still taken.
    public void fillQueue() {
        queueFull = true;
    }

    /// Takes immediate tasks again, as an executor whose queue has emptied does.
    public void emptyQueue() {
        queueFull = false;
    }

    @Override
    public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
        checkTaken("a submitted task");
        return delegate.submit(task);
    }

    @Override
    public void execute(String taskName, Runnable command) {
        checkTaken(taskName);
        delegate.execute(taskName, command);
    }

    @Override
    public boolean tryExecute(String taskName, Runnable command) {
        if (isClosed()) {
            return false;
        }
        checkTaken(taskName);
        return delegate.tryExecute(taskName, command);
    }

    @Override
    public Closeable schedule(Duration delay, Runnable command) {
        if (isClosed()) {
            throw new RejectedExecutionException("Rejected a delayed task: shut down");
        }
        return delegate.schedule(delay, command);
    }

    @Override
    public Closeable scheduleAtFixedRate(Duration initialDelay, Duration period, Runnable command) {
        if (isClosed()) {
            throw new RejectedExecutionException("Rejected a periodic task: shut down");
        }
        return delegate.scheduleAtFixedRate(initialDelay, period, command);
    }

    private void checkTaken(String taskName) {
        boolean closed = isClosed();
        if (closed || queueFull) {
            throw new RejectedExecutionException("Rejected " + taskName + (closed ? ": shut down" : ": queue is full"));
        }
    }
}
