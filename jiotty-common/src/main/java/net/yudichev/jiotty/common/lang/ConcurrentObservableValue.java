package net.yudichev.jiotty.common.lang;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

/// Thread-safe [ObservableValue] implementation. All methods are safe to call from any thread.
///
/// When multiple threads call [#accept] concurrently, all values are delivered to all subscribers, but the delivery order is nondeterministic.
///
/// Observers may safely subscribe, unsubscribe, or push new values from within their notification callbacks.
///
/// Actions are serialised through one queue drained by whichever thread finds it idle, so a [#subscribe(Consumer)] landing while another thread is delivering
/// is completed by that thread: the new observer gets the current value promptly, and can get it after [#subscribe(Consumer)] has returned. This is guarantee
/// 1 of [ObservableValue] — read that for what a caller may assume.
///
/// What an observer throws goes to the `listenerFailureHandler`, and delivery to every observer, that one included, carries on.
public final class ConcurrentObservableValue<T> implements ObservableValue<T> {

    private final ConcurrentLinkedQueue<Runnable> actionQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger wip = new AtomicInteger();
    private final Consumer<? super RuntimeException> listenerFailureHandler;

    @SuppressWarnings("FieldAccessedSynchronizedAndUnsynchronized")
    private volatile T value;
    private volatile LinkedHashSet<Consumer<? super T>> listeners = new LinkedHashSet<>();

    /// @param listenerFailureHandler told of what an observer throws, on whichever thread is delivering at the time — any caller of [#accept],
    ///                               [#subscribe(Consumer)] or a subscription's [Closeable#close()]; must not throw
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
