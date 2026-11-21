package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/// A value that changes over time, whose current value [#get()] returns, and whose listeners receive each new value.
///
/// @param <T> the type of the value
public interface Observable<T> extends Supplier<T> {
    /// Subscribes `listener`, which first receives the current value and then every later value.
    ///
    /// Depending on the implementation, `listener` may receive the current value re-entrantly or after this method returns,
    /// and on this or on another thread.
    ///
    /// Closing the returned handle ends the deliveries to `listener`.
    Closeable subscribe(Consumer<? super T> listener);

    /// Subscribes `listener`, which first receives the current value and then every later value. This sequence is only guaranteed if every change of the
    /// value is strictly sequential with the tasks of `executor` (same thread, or synchronised).
    ///
    /// This method may be called on any thread, for any implementation.
    ///
    /// Closing the returned handle, on any thread, ends the deliveries to `listener`. Depending on the thread and the timing of the closure, a value set
    /// before the closure, the current value included, may or may not reach `listener`.
    ///
    /// An exception that `listener` throws while receiving the current value reaches the failure handler of `executor`.
    ///
    /// @param executor the executor on which `listener` receives the current value
    Closeable subscribe(TaskExecutor executor, Consumer<? super T> listener);

    int subscriberCount();
}
