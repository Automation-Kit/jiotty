package net.yudichev.jiotty.common.lang;

import java.util.function.Consumer;

/// An implementation of an observable value that notifies observers about all value changes that happened after the observer was
/// [subscribed](#subscribe(Consumer)), with image delivery. Has the following guarantees:
/// 1. When the new observer is added via [#subscribe(Consumer)], it immediately receives the current value.
/// 2. When the value is changed via [#accept(T)], all observers are notified about the change. No changes are ever missed by any subscribed observers, i.e. no
/// conflation.
/// 3. When the observer is unsubscribed, it stops receiving further notifications.
public interface ObservableValue<T> extends Consumer<T>, Observable<T> {
    /// Creates [ConcurrentObservableValue]
    static <T> ObservableValue<T> concurrent(T initialValue) {
        return new ConcurrentObservableValue<>(initialValue);
    }

    static <T> ObservableValue<T> simple(T initialValue) {
        return new SimpleObservableValue<>(initialValue);
    }

    void setNotificationsSuppressed(boolean suppressed);
}
