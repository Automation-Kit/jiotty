package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompositeException;

import java.util.function.Consumer;

/// An [Observable] whose value [#accept(T)] sets. Every subscribed listener receives every value passed to [#accept(T)], a value equal to the current one
/// included, while [notifications are not suppressed][#setNotificationsSuppressed(boolean)]. The listeners receive the values in the order of the calls to
/// [#accept(T)] if those calls are strictly sequential (same thread, or synchronised).
///
/// There are two implementations provided, a [single-threaded][#simple] one and a [multi-threaded][#concurrent] one, each having slightly different guarantees,
/// and different performance characteristics.
///
/// @param <T> the type of the value
public interface ObservableValue<T> extends Consumer<T>, Observable<T> {
    /// Creates an [ObservableValue] whose methods may be called on any thread. For purely single-threaded use, prefer the [single-threaded][#simple]
    /// implementation, which is more efficient.
    ///
    /// A listener subscribed through [#subscribe(Consumer)] is called on the thread of some caller of [#accept(T)], [#subscribe(Consumer)] or a handle's
    /// [Closeable#close()], which may be a different caller from the one that passed the value. A listener subscribed through
    /// [#subscribe(TaskExecutor, Consumer)] is called on its executor, and an exception that it throws reaches the failure handler of that executor. A
    /// listener may subscribe, unsubscribe and call [#accept(T)] while it is being notified.
    ///
    /// The created value does not support [#setNotificationsSuppressed(boolean)].
    ///
    /// @param <T>                    the type of the value
    /// @param listenerFailureHandler invoked with each exception that a listener subscribed through [#subscribe(Consumer)] throws, on the thread on which that
    ///                               listener is called; the other listeners still receive the value, and every listener receives later values. Must not
    ///                               throw.
    static <T> ObservableValue<T> concurrent(T initialValue, Consumer<? super RuntimeException> listenerFailureHandler) {
        return new ConcurrentObservableValue<>(initialValue, listenerFailureHandler);
    }

    /// Creates an [ObservableValue] whose methods must all be called on one thread, except for these, which may be called on any thread:
    ///
    /// - [#subscribe(TaskExecutor, Consumer)];
    /// - [Closeable#close()] on the handle that [#subscribe(TaskExecutor, Consumer)] returns.
    ///
    /// [#subscribe(TaskExecutor, Consumer)] adds a thread to the created value: it subscribes the listener, and calls it with the current value, on the
    /// executor. The tasks of the executor must therefore be strictly sequential with the calls to the other methods (same thread, or synchronised), and so
    /// must every call that the listener makes to the created value.
    ///
    /// A listener may subscribe, unsubscribe and call [#accept(T)] while it is being notified. A value that it passes to [#accept(T)] becomes the current
    /// value, and reaches the listeners, once the value being delivered has reached every listener.
    ///
    /// When a listener throws an exception while receiving a value passed to [#accept(T)], every other listener still receives that value, and then
    /// [#accept(T)] throws that exception, or a [CompositeException] of the exceptions of several listeners. Every listener stays subscribed.
    ///
    /// A listener that throws an exception while receiving the current value stays subscribed. [#subscribe(Consumer)] throws that exception, and
    /// [#subscribe(TaskExecutor, Consumer)] passes it to the failure handler of the executor.
    ///
    /// @param <T> the type of the value
    static <T> ObservableValue<T> simple(T initialValue) {
        return new SimpleObservableValue<>(initialValue);
    }

    /// Suppresses or resumes the notifications. While they are suppressed, a listener receives nothing, and when they resume, each listener that missed a
    /// value receives the latest value it missed.
    ///
    /// @throws UnsupportedOperationException if this value does not support suppression
    void setNotificationsSuppressed(boolean suppressed);
}
