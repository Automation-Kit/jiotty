package net.yudichev.jiotty.common.observer;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import org.jspecify.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/// The handle of a subscription made by a task on an executor, to an observable whose subscriber list is confined to that executor. Closing this handle, on
/// any thread, queues a task on the same executor that closes the subscription, and does nothing once that executor has shut down.
///
/// The executor runs its tasks in order, so the closing task runs after the task that made the subscription, and closes what that task passed to [#set].
// FieldAccessNotGuarded: the guard is the single-threaded executor rather than a lock, so IntelliJ cannot see that every access runs on it.
@SuppressWarnings("FieldAccessNotGuarded")
final class ExecutorSubscription extends BaseIdempotentCloseable {
    private final TaskExecutor executor;
    private final String unsubscribeTaskName;
    /// The subscription that [#set] stores; `null` until [#set] is called, and stays `null` if the subscribing task fails before calling it.
    @GuardedBy("executor")
    private @Nullable Closeable subscription;

    /// @param unsubscribeTaskName names the closing task in a failure report
    public ExecutorSubscription(TaskExecutor executor, String unsubscribeTaskName) {
        this.executor = checkNotNull(executor, "executor");
        this.unsubscribeTaskName = checkNotNull(unsubscribeTaskName, "unsubscribeTaskName");
    }

    @Override
    protected void doClose() {
        executor.tryExecute(unsubscribeTaskName, () -> Closeable.closeIfNotNull(subscription));
    }

    /// Stores `subscription` for the closing task to close. Must be called by the subscribing task, on the executor passed to the constructor.
    public void set(Closeable subscription) {
        this.subscription = subscription;
    }
}
