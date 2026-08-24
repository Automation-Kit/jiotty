package net.yudichev.jiotty.common.async;

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

            /// Runs `command` on the calling thread, letting a failure propagate to the caller — which is the caller's own stack here, so there is nothing to
            /// report it to.
            @Override
            public void execute(String taskName, Runnable command) {
                command.run();
            }
        };
    }
}
