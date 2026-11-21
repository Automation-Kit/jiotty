package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import com.google.common.collect.Sets;
import net.yudichev.jiotty.common.async.TaskExecutor;

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

    /// Adds `consumer` in a task on `executor`, and in the same task delivers to `consumer` the value that `imageSupplier` supplies, if present. Closing the
    /// returned handle removes `consumer` in a task on the same executor, and does nothing if that executor is already closed, as
    /// [Observable#subscribe(TaskExecutor, Consumer)] does; that method's doc gives the reason. An exception reaches the failure handler of `executor` when:
    ///
    /// - `imageSupplier` throws it, and then `consumer` is not added;
    /// - `consumer` throws it on the image, and then `consumer` stays added and receives every later [#notify], as it does after throwing on any of them.
    public Closeable addListener(TaskExecutor executor, Supplier<Optional<T>> imageSupplier, Consumer<? super T> consumer) {
        // Only tasks on `executor` write and read `handle`.
        var handle = new MutableReference<Closeable>();
        executor.execute("addListener", () -> {
            Optional<T> image = imageSupplier.get();
            handle.set(addListener(consumer));
            image.ifPresent(consumer);
        });
        return Closeable.idempotent(() -> executor.tryExecute("removeListener", () -> Closeable.closeIfNotNull(handle.get())));
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
