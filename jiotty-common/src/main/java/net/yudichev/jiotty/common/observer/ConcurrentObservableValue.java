package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    private final AtomicReference<T> value;
    private volatile LinkedHashSet<Consumer<? super T>> listeners = new LinkedHashSet<>();

    /// @param listenerFailureHandler invoked with each exception that a listener subscribed through [#subscribe(Consumer)] throws, on the thread on which that
    ///                               listener is called; the other listeners still receive the value, and every listener receives later values. Must not
    ///                               throw.
    public ConcurrentObservableValue(T initialValue, Consumer<? super RuntimeException> listenerFailureHandler) {
        // Every action may read initialValue plainly: value is final and is set here, before the constructor returns.
        value = new AtomicReference<>(initialValue);
        this.listenerFailureHandler = checkNotNull(listenerFailureHandler, "listenerFailureHandler");
    }

    /// Returns the current value.
    ///
    /// A value passed to [#accept] becomes the current value either before [#accept] returns or later, on the thread of a concurrent call of [#accept],
    /// [#subscribe(Consumer)] or a handle's [Closeable#close()]. Until then, this method returns the earlier value, on every thread, including the one that
    /// called [#accept].
    @Override
    public T get() {
        // Acquire: every write to value after construction is the release in accept(), and this read pairs with it, so the caller reads the latest value
        // written and sees everything written before that value.
        return value.getAcquire();
    }

    @Override
    public Closeable subscribe(Consumer<? super T> listener) {
        actionQueue.add(() -> {
            var newListeners = new LinkedHashSet<>(listeners);
            newListeners.add(listener);
            listeners = newListeners;
            // Plain: the actions run one at a time, and wip orders every earlier action, the write in accept() included, before this one.
            listener.accept(value.getPlain());
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
    public void accept(T newValue) {
        actionQueue.add(() -> {
            // Release: get() reads value with an acquire that pairs with this write, and the actions, which wip runs one after another, read it plainly.
            // Both reads see this write and everything written before it.
            value.setRelease(newValue);
            runForAll(listeners, listener -> listener.accept(newValue));
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
