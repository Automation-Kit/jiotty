package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;
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
        execute("task", command);
    }

    /// Executes `command` on this executor's thread, tagging it `taskName` so an uncaught failure is reported against a name a reader recognises.
    default void execute(String taskName, Runnable command) {
        submit(guarded(logger, taskName, command));
    }

    /// Executes `command` as [#execute(String, Runnable)] does when this executor can take it, and discards it otherwise, reporting which happened. Use this
    /// for work whose caller has established it is safe to drop — canonically a callback from a producer that outlives the caller, which can arrive once this
    /// executor is closed. Work carrying a durability or compliance obligation goes through [#execute(String, Runnable)], which fails loudly.
    ///
    /// @return `true` if `command` was queued, `false` if it was discarded
    default boolean tryExecute(String taskName, Runnable command) {
        execute(taskName, command);
        return true;
    }

    /// Queues the closing of `resources` on this executor's thread and returns, for resources confined to that thread. Each resource closes independently,
    /// a failure in one being logged and the others closed, as [Closeable#closeSafelyIfNotNull(Logger, Closeable...)] does.
    ///
    /// @param taskName       identifies the work in a failure report
    /// @param resourceLogger the caller's logger, so a close failure is attributed to the component that owned the resource
    default void executeClose(String taskName, Logger resourceLogger, Closeable... resources) {
        execute(taskName, () -> Closeable.closeSafelyIfNotNull(resourceLogger, resources));
    }

    static Callable<Void> toCallable(Runnable command) {
        return () -> {
            command.run();
            return null;
        };
    }
}
