package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.inject.LifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static net.yudichev.jiotty.common.lang.Runnables.guarded;

public interface TaskExecutor extends Executor {
    Logger logger = LogManager.getLogger(TaskExecutor.class);

    <T> CompletableFuture<T> submit(Callable<? extends T> task);

    default CompletableFuture<Void> submit(Runnable command) {
        return submit(toCallable(command));
    }

    @Override
    default void execute(Runnable command) {
        submit(guarded(logger, "task", command));
    }

    /// Execute the specified command on the executor's thread and await its completion if the task with the specified timeout, but **only** if the executor is
    /// backed by the unique live thread. This is designed to be used in [LifecycleComponent#stop()] implementations so that the shutdown is happening on the
    /// executor's thread and completes fully before the method completes, while at the same time being compatible with [ProgrammableClock].
    void executeAndAwaitIfLive(Runnable command, Duration timeout);

    static Callable<Void> toCallable(Runnable command) {
        return () -> {
            command.run();
            return null;
        };
    }
}
