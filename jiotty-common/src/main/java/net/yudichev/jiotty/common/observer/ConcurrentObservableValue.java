package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

/// An [ObservableValue] whose methods may be called on any thread.
///
/// A listener subscribed through [#subscribe(Consumer)] is called on the thread of some caller of [#accept], [#subscribe(Consumer)] or a handle's
/// [Closeable#close()], which may be a different caller from the one that passed the value. A listener subscribed through
/// [#subscribe(TaskExecutor, Consumer)] is called on its executor, and an exception that it throws reaches the failure handler of that executor. A listener
/// may subscribe, unsubscribe and call [#accept] while it is being notified.
///
/// This value does not support [#setNotificationsSuppressed(boolean)].
///
/// @param <T> the type of the value
public final class ConcurrentObservableValue<T> implements ObservableValue<T> {

    private final ConcurrentLinkedQueue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger wip = new AtomicInteger();
    private final Consumer<? super RuntimeException> listenerFailureHandler;

    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
    private volatile T value;
    private volatile LinkedHashSet<Consumer<? super T>> listeners = new LinkedHashSet<>();

    /// @param listenerFailureHandler invoked with each exception that a listener subscribed through [#subscribe(Consumer)] throws, on the thread on which that
    ///                               listener is called; the other listeners still receive the value, and every listener receives later values. Must not
    ///                               throw.
    public ConcurrentObservableValue(T initialValue, Consumer<? super RuntimeException> listenerFailureHandler) {
        value = initialValue;
        this.listenerFailureHandler = checkNotNull(listenerFailureHandler, "listenerFailureHandler");
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public Closeable subscribe(Consumer<? super T> listener) {
        actionQueue.add(() -> {
            var newListeners = new LinkedHashSet<>(listeners);
            newListeners.add(listener);
            listeners = newListeners;
            listener.accept(value);
        });
        drainAfterAddingAction();
        return Closeable.idempotent(() -> {
            actionQueue.add(() -> {
                var newListeners = new LinkedHashSet<>(listeners);
                newListeners.remove(listener);
                listeners = newListeners;
            });
            drainAfterAddingAction();
        });
    }

    @Override
    public Closeable subscribe(TaskExecutor executor, Consumer<? super T> listener) {
        return subscribe(value -> executor.tryExecute("deliver", () -> listener.accept(value)));
    }

    @Override
    public int subscriberCount() {
        return listeners.size();
    }

    @Override
    public void accept(T value) {
        actionQueue.add(() -> {
            this.value = value;
            runForAll(listeners, listener -> listener.accept(value));
        });
        drainAfterAddingAction();
    }

    /// Runs the queued actions in order, one at a time. A call made while no other call is running the actions runs every action queued until none is left,
    /// including those that other threads and the actions themselves queue meanwhile; every other call returns at once.
    private void drainAfterAddingAction() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        do {
            Runnable action;
            while ((action = actionQueue.poll()) != null) {
                // Caught per action, so the actions other threads queued behind it still run and wip returns to zero.
                try {
                    action.run();
                } catch (RuntimeException e) {
                    listenerFailureHandler.accept(e);
                }
            }
        } while (wip.decrementAndGet() != 0);
    }

    @Override
    public String toString() {
        return Objects.toString(get());
    }

    @Override
    public void setNotificationsSuppressed(boolean suppressed) {
        throw new UnsupportedOperationException("setNotificationsSuppressed");
    }
}
