package net.yudichev.jiotty.common.async;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    static Callable<Void> toCallable(Runnable command) {
        return () -> {
            command.run();
            return null;
        };
    }
}
