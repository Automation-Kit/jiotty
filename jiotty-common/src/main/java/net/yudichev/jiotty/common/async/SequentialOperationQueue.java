package net.yudichev.jiotty.common.async;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

/// A queue of asynchronous operations that run one after another on the executor it was created with: each starts once the one submitted before it has
/// finished, however that one ended. A caller therefore always gets the outcome of an operation that began after its call, and whatever state the operations
/// write has one writer at a time even across the asynchronous steps each operation waits on.
@SuppressWarnings("FieldAccessNotGuarded") // @GuardedBy("executor") marks executor confinement, which IntelliJ's lock analysis cannot see
public final class SequentialOperationQueue {
    private final TaskExecutor executor;
    @GuardedBy("executor")
    private CompletableFuture<?> lastOperation = completedFuture(null);

    public SequentialOperationQueue(TaskExecutor executor) {
        this.executor = checkNotNull(executor);
    }

    /// @param <T> the type of the operation's value
    /// @return completes as the operation does; cancelling it leaves the queue waiting for the operation all the same
    public <T> CompletableFuture<T> submit(Supplier<? extends CompletableFuture<T>> operation) {
        return executor.submit(() -> {
            CompletableFuture<T> result = lastOperation.handle((_, _) -> null).thenComposeAsync(_ -> operation.get(), executor);
            lastOperation = result;
            return result;
        }).thenCompose(Function.identity());
    }
}
