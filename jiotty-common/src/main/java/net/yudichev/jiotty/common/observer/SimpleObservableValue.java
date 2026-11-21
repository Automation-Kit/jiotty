package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompositeException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

final class SimpleObservableValue<T> implements ObservableValue<T> {
    private final ListenerList<SuppressableListener<? super T>> listeners = new ListenerList<>();
    /// The values passed to [#accept] while a notification ran, in the order of the calls, each waiting to become the current value until no notification
    /// is in progress; `null` until the first such value.
    private @Nullable List<T> deferredValues;
    private T value;
    private boolean notificationsSuppressed;

    public SimpleObservableValue(T initialValue) {
        value = initialValue;
    }

    @Override
    public Closeable subscribe(Consumer<? super T> listener) {
        SuppressableListener<? super T> suppressableListener = addListener(listener);
        Closeable closer = createCloser(suppressableListener);
        deliverCurrentValue(suppressableListener);
        return closer;
    }

    @Override
    public Closeable subscribe(TaskExecutor executor, Consumer<? super T> listener) {
        var subscription = new ExecutorSubscription(executor, "unsubscribe");
        executor.execute("subscribe", () -> {
            if (subscription.isClosed()) {
                return;
            }
            SuppressableListener<? super T> suppressableListener = addListener(listener);
            subscription.set(createCloser(suppressableListener));
            deliverCurrentValue(suppressableListener);
        });
        return subscription;
    }

    private SuppressableListener<? super T> addListener(Consumer<? super T> listener) {
        var suppressableListener = new SuppressableListener<>(listener, notificationsSuppressed);
        listeners.add(suppressableListener);
        return suppressableListener;
    }

    private Closeable createCloser(SuppressableListener<? super T> suppressableListener) {
        return Closeable.idempotent(() -> listeners.remove(suppressableListener));
    }

    private void deliverCurrentValue(SuppressableListener<? super T> listener) {
        List<RuntimeException> failures = null;
        listeners.startNotification();
        try {
            listener.accept(value);
        } catch (RuntimeException e) {
            failures = withFailure(null, e);
        } finally {
            listeners.endNotification();
        }
        throwFailures(deliverDeferredValues(failures));
    }

    @Override
    public int subscriberCount() {
        return listeners.size();
    }

    @Override
    public void accept(T newValue) {
        if (listeners.isNotifying()) {
            if (deferredValues == null) {
                deferredValues = new ArrayList<>();
            }
            deferredValues.add(newValue);
        } else {
            throwFailures(deliverDeferredValues(deliver(newValue, null)));
        }
    }

    @Override
    public T get() {
        return value;
    }

    @Override
    public String toString() {
        return Objects.toString(value);
    }

    @Override
    public void setNotificationsSuppressed(boolean notificationsSuppressed) {
        this.notificationsSuppressed = notificationsSuppressed;
        throwFailures(deliverDeferredValues(forEachListener(SuppressableListener::setNotificationsSuppressed, notificationsSuppressed, null)));
    }

    /// Delivers each deferred value in the order of the calls to [#accept], unless a notification is still in progress.
    ///
    /// @param failures the exceptions that listeners threw earlier in the same call to a public method, or `null` for none
    /// @return `failures` with the exceptions that the listeners threw on the deferred values added, or `null` if there are none
    private @Nullable List<RuntimeException> deliverDeferredValues(@Nullable List<RuntimeException> failures) {
        List<RuntimeException> allFailures = failures;
        if (!listeners.isNotifying() && deferredValues != null) {
            // A value that a listener passes to [#accept] during this loop joins the deferred values, and this loop delivers it in its turn.
            //noinspection ForLoopReplaceableByForEach an iterator would fail on a value deferred during the loop
            for (int i = 0; i < deferredValues.size(); i++) {
                allFailures = deliver(deferredValues.get(i), allFailures);
            }
            deferredValues.clear();
        }
        return allFailures;
    }

    /// Makes `newValue` the current value and delivers it to each subscribed listener.
    ///
    /// @param failures the exceptions that listeners threw earlier in the same call to a public method, or `null` for none
    /// @return `failures` with the exceptions that the listeners threw on `newValue` added, or `null` if there are none
    private @Nullable List<RuntimeException> deliver(T newValue, @Nullable List<RuntimeException> failures) {
        value = newValue;
        return forEachListener(SuppressableListener::accept, newValue, failures);
    }

    /// Calls `action` with each subscribed listener and `argument`. When `action` throws for one listener, every other listener still receives the call.
    ///
    /// @param <A>      the type of the argument passed to `action` with each listener
    /// @param failures the exceptions that listeners threw earlier in the same call to a public method, or `null` for none
    /// @return `failures` with the exceptions that `action` threw added, or `null` if there are none
    private <A> @Nullable List<RuntimeException> forEachListener(BiConsumer<? super SuppressableListener<? super T>, ? super A> action,
                                                                 A argument,
                                                                 @Nullable List<RuntimeException> failures) {
        List<RuntimeException> allFailures = failures;
        List<@Nullable SuppressableListener<? super T>> notified = listeners.startNotification();
        try {
            // A listener subscribed during this loop received the current value and suppression when the listener subscribed, so the loop ends at the
            // listeners that were subscribed when the loop started.
            int listenerCount = notified.size();
            //noinspection ForLoopReplaceableByForEach an iterator would fail on a listener subscribed during the loop, and would reach it
            for (int i = 0; i < listenerCount; i++) {
                SuppressableListener<? super T> listener = notified.get(i);
                if (listener != null) {
                    try {
                        action.accept(listener, argument);
                    } catch (RuntimeException e) {
                        allFailures = withFailure(allFailures, e);
                    }
                }
            }
        } finally {
            listeners.endNotification();
        }
        return allFailures;
    }

    private static List<RuntimeException> withFailure(@Nullable List<RuntimeException> failures, RuntimeException failure) {
        List<RuntimeException> result = failures == null ? new ArrayList<>() : failures;
        result.add(failure);
        return result;
    }

    /// @param failures the exceptions to throw, or `null` for none
    private static void throwFailures(@Nullable List<RuntimeException> failures) {
        if (failures != null) {
            CompositeException.throwIfAny(failures);
        }
    }

    private static class SuppressableListener<T> implements Consumer<T> {
        private final Consumer<T> delegate;
        private boolean suppressed;
        private T pendingValue;
        private boolean pendingValueSet;

        public SuppressableListener(Consumer<T> delegate, boolean suppressed) {
            this.delegate = checkNotNull(delegate);
            this.suppressed = suppressed;
        }

        @Override
        public void accept(T value) {
            if (suppressed) {
                pendingValue = value;
                pendingValueSet = true;
            } else {
                delegate.accept(value);
            }
        }

        public void setNotificationsSuppressed(boolean suppressed) {
            boolean wasSuppressed = this.suppressed;
            this.suppressed = suppressed;
            if (wasSuppressed && !suppressed && pendingValueSet) {
                delegate.accept(pendingValue);
                pendingValueSet = false;
                //noinspection AssignmentToNull this is to help GC only
                pendingValue = null;
            }
        }
    }
}
