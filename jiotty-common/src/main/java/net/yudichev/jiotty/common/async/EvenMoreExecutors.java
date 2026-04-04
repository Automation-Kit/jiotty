package net.yudichev.jiotty.common.async;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class EvenMoreExecutors {
    private EvenMoreExecutors() {
    }

    public static TaskExecutor directExecutor() {
        return new TaskExecutor() {

            @Override
            public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
                return completedFuture(getAsUnchecked(task::call));
            }

            @Override
            public void executeAndAwaitIfLive(Runnable command, Duration timeout) {
                execute(command);
            }
        };
    }
}
