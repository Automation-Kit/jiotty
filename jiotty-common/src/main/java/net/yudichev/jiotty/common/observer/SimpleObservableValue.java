package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.lang.Closeable;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

final class SimpleObservableValue<T> implements ObservableValue<T> {
    private final Set<SuppressableListener<? super T>> listeners = new LinkedHashSet<>();
    private T value;
    private boolean notificationsSuppressed;

    public SimpleObservableValue(T initialValue) {
        value = initialValue;
    }

    @Override
    public Closeable subscribe(Consumer<? super T> listener) {
        var suppressableListener = new SuppressableListener<>(listener, notificationsSuppressed);
        listeners.add(suppressableListener);
        suppressableListener.accept(value);
        return Closeable.idempotent(() -> listeners.remove(suppressableListener));
    }

    @Override
    public int subscriberCount() {
        return listeners.size();
    }

    @Override
    public void accept(T newValue) {
        value = newValue;
        runForAll(listeners, listener -> listener.accept(value));
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
        listeners.forEach(listener -> listener.setNotificationsSuppressed(notificationsSuppressed));
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
