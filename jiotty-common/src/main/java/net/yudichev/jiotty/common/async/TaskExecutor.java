package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.failedFuture;

public interface TaskExecutor extends Executor {
    <T> CompletableFuture<T> submit(Callable<? extends T> task);

    default CompletableFuture<Void> submit(Runnable command) {
        return submit(toCallable(command));
    }

    /// Submits `task` as [#submit(Callable)] does, except that a [RejectedExecutionException] fails the returned future instead of being thrown.
    default <T> CompletableFuture<T> submitOrFail(Callable<? extends T> task) {
        try {
            return submit(task);
        } catch (RejectedExecutionException e) {
            return failedFuture(e);
        }
    }

    /// Submits `command` as [#submit(Runnable)] does, except that a [RejectedExecutionException] fails the returned future instead of being thrown.
    default CompletableFuture<Void> submitOrFail(Runnable command) {
        return submitOrFail(toCallable(command));
    }

    @Override
    default void execute(Runnable command) {
        execute("task", command);
    }

    /// Executes `command` on this executor's thread, tagging it `taskName` so an uncaught failure is reported against a name a reader recognises.
    ///
    /// @implSpec An implementation reports an uncaught throwable from `command` through [TaskFailureReporter], which is where the host application escalates
    /// task failures from, or lets it propagate to whoever drives this executor.
    void execute(String taskName, Runnable command);

    /// Executes `command` as [#execute(String, Runnable)] does, unless this executor has shut down.
    ///
    /// @return `false` if this executor has shut down, so `command` was not queued
    /// @throws RejectedExecutionException if this executor's queue is full
    default boolean tryExecute(String taskName, Runnable command) {
        execute(taskName, command);
        return true;
    }

    /// Applies `continuation` to the outcome of `source` in a task on this executor, if `condition` holds when that task runs. A shut-down executor discards
    /// the task.
    ///
    /// @param <T>       the type of `source`'s value
    /// @param <U>       the type of the continuation's value
    /// @param condition read in the task on this executor, immediately before `continuation` would run; `false` skips `continuation`
    /// @param taskName  identifies the continuation in a failure report
    /// @return the continuation's outcome, or a future that never completes when `condition` does not hold or this executor has shut down
    default <T, U> CompletableFuture<U> handleWhile(BooleanSupplier condition,
                                                    CompletableFuture<T> source,
                                                    String taskName,
                                                    BiFunction<? super T, ? super Throwable, ? extends CompletionStage<U>> continuation) {
        return source.<CompletionStage<U>>handleAsync((value, failure) -> condition.getAsBoolean()
                                                                          ? continuation.apply(value, failure)
                                                                          : new CompletableFuture<>(),
                                                      command -> tryExecute(taskName, command))
                     .thenCompose(Function.identity());
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
