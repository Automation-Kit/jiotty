package net.yudichev.jiotty.common.lang;

import net.yudichev.jiotty.common.async.TaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface Observable<T> extends Supplier<T> {
    Closeable subscribe(Consumer<? super T> listener);

    /// Subscribes from `executor`'s thread and delivers every notification on it, for an observable whose state is confined to that executor. The caller gets
    /// its handle without waiting, and `listener` receives the current value first, ordered ahead of any later one.
    ///
    /// Closing the returned handle unsubscribes on the same executor, and tolerates that executor already being closed: teardown drains executors last, so a
    /// subscriber routinely releases its handle after the observable's owner has stopped, and unsubscribing from something that has stopped publishing is
    /// work worth dropping.
    default Closeable subscribe(TaskExecutor executor, Consumer<? super T> listener) {
        CompletableFuture<Closeable> subscription = executor.submit(() -> subscribe(listener));
        return Closeable.idempotent(() -> executor.tryExecute("unsubscribe", () -> subscription.thenAccept(Closeable::close)));
    }

    int subscriberCount();
}
