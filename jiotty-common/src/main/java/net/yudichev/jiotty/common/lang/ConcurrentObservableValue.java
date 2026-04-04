package net.yudichev.jiotty.common.lang;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

/// Thread-safe [ObservableValue] implementation. All methods are safe to call from any thread.
///
/// When multiple threads call [#accept] concurrently, all values are delivered to all subscribers, but the delivery order is nondeterministic.
///
/// Observers may safely subscribe, unsubscribe, or push new values from within their notification callbacks.
public final class ConcurrentObservableValue<T> implements ObservableValue<T> {

    private final ConcurrentLinkedQueue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger wip = new AtomicInteger();

    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
    private volatile T value;
    private volatile LinkedHashSet<Consumer<? super T>> listeners = new LinkedHashSet<>();

    public ConcurrentObservableValue(T initialValue) {
        value = initialValue;
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

    /// Drain loop: serializes all enqueued actions. Only the thread that increments wip from 0→1 enters the loop. Reentrant and concurrent calls increment wip
    /// and return; the draining thread re-checks after each pass.
    private void drainAfterAddingAction() {
        if (wip.getAndIncrement() != 0) {
            return;
        }
        do {
            Runnable action;
            while ((action = actionQueue.poll()) != null) {
                action.run();
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
