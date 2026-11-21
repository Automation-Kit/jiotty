package net.yudichev.jiotty.common.observer;

import com.google.common.collect.Sets;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

public final class Listeners<T> {
    private final Set<Consumer<? super T>> listeners = Sets.newConcurrentHashSet();

    public Closeable addListener(Consumer<? super T> consumer) {
        checkArgument(listeners.add(consumer), "listener already added: %s", consumer);
        return Closeable.idempotent(() -> listeners.remove(consumer));
    }

    /// Adds `consumer` to the listeners. The newly added `consumer` first receives the current value that `imageSupplier` supplies, if present, and then every
    /// later [#notify]. This sequence is only guaranteed if every call to [#notify] is strictly sequential with the tasks of `executor` (same thread, or
    /// synchronised).
    ///
    /// `consumer` receives each later value on the thread that calls [#notify], which is `executor` while every call to [#notify] is made there.
    ///
    /// Closing the returned handle, on any thread, ends the deliveries to `consumer`. Depending on the thread and the timing of the closure, the image that
    /// `imageSupplier` supplies, or a value passed to [#notify] before the closure, may or may not reach `consumer`.
    ///
    /// An exception reaches the failure handler of `executor` when:
    ///
    /// - `imageSupplier` throws it (and then `consumer` receives nothing);
    /// - `consumer` throws it on the image, and then `consumer` still receives every later [#notify], as it does after throwing on any of them.
    ///
    /// @param executor the executor on which `imageSupplier` is called and `consumer` receives the image
    public Closeable addListener(TaskExecutor executor, Supplier<Optional<T>> imageSupplier, Consumer<? super T> consumer) {
        var subscription = new ExecutorSubscription(executor, "removeListener");
        executor.execute("addListener", () -> {
            if (subscription.isClosed()) {
                return;
            }
            Optional<T> image = imageSupplier.get();
            subscription.set(addListener(consumer));
            image.ifPresent(consumer);
        });
        return subscription;
    }

    public void notify(T value) {
        runForAll(listeners, listener -> listener.accept(value));
    }

    public void clear() {
        listeners.clear();
    }

    public boolean isEmpty() {
        return listeners.isEmpty();
    }

    public int size() {
        return listeners.size();
    }
}
