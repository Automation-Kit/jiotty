package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;

/// The subscriptions to a publisher that publishes an image of its state and then each change to that state.
///
/// A listener that throws on a delivery is removed and passed to the failure handler, and the other listeners still receive the delivery. A listener that
/// throws may not have applied the change, and then holds a wrong copy of the state until it subscribes again and receives a new image. If each delivery is
/// the whole current value, use [Observable#subscribe(TaskExecutor, Consumer)] or [Listeners#addListener(TaskExecutor, Supplier, Consumer)] instead, both of
/// which keep a listener that throws.
///
/// @param <L> the type of the listeners, which have a method for each kind of delivery
public final class SerialisedListeners<L> {
    private final ListenerList<Subscription> subscriptions = new ListenerList<>();

    /// Subscribes `listener`, which first receives the image through `imageDelivery`, and then every later [#publish] until the subscription is closed or
    /// [#removeAll] delivers its last notification. This sequence is only guaranteed if every call to [#publish] and [#removeAll] is strictly sequential
    /// with the tasks of `executor` (same thread, or synchronised).
    ///
    /// The subscriptions are confined to `executor`. This method may be called on any thread, and so may [Closeable#close()] on the subscription that it
    /// returns. [#publish], [#removeAll] and [#size] must be called on `executor`.
    ///
    /// Closing the returned subscription ends the deliveries to `listener`. Depending on the thread and the timing of the closure, the image may or may not
    /// have been delivered.
    ///
    /// @param executor       the executor on which `imageDelivery` and `failureHandler` are called
    /// @param imageDelivery  delivers the image to the listener, and may read the image itself
    /// @param failureHandler invoked with the listener and the exception when `imageDelivery` throws; the subscription is then not added
    public Closeable subscribe(TaskExecutor executor,
                               L listener,
                               Consumer<? super L> imageDelivery,
                               BiConsumer<? super L, RuntimeException> failureHandler) {
        var subscription = new Subscription(executor, listener);
        executor.execute("subscribe", () -> add(subscription, imageDelivery, failureHandler));
        return subscription;
    }

    private void add(Subscription subscription, Consumer<? super L> imageDelivery, BiConsumer<? super L, RuntimeException> failureHandler) {
        if (subscription.isClosed()) {
            return;
        }
        try {
            imageDelivery.accept(subscription.listener);
        } catch (RuntimeException e) {
            failureHandler.accept(subscription.listener, e);
            return;
        }
        subscriptions.add(subscription);
    }

    /// Delivers a notification to the listener of each added subscription that is still open. A listener may close a subscription, and a subscription may be
    /// added, as a listener is notified; a subscription added then does not receive this notification. A listener may also call [#publish] and [#removeAll]
    /// as it is notified, and after [#removeAll] no listener receives this notification.
    ///
    /// @param failureHandler invoked with each listener that throws and the exception; the listener's subscription is removed first
    public void publish(Consumer<? super L> notification, BiConsumer<? super L, RuntimeException> failureHandler) {
        List<@Nullable Subscription> notified = subscriptions.startNotification();
        try {
            int subscriptionCount = notified.size();
            // A listener that calls [#removeAll] as it is notified has had every listener receive its last notification, so the loop stops there.
            for (int i = 0; i < subscriptionCount && subscriptions.holdsListeners(notified); i++) {
                Subscription subscription = notified.get(i);
                if (subscription != null) {
                    if (subscription.isClosed()) {
                        subscriptions.removeAt(notified, i);
                    } else {
                        try {
                            notification.accept(subscription.listener);
                        } catch (RuntimeException e) {
                            subscriptions.removeAt(notified, i);
                            failureHandler.accept(subscription.listener, e);
                        }
                    }
                }
            }
        } finally {
            subscriptions.endNotification();
        }
    }

    /// Removes every added subscription, and delivers `lastNotification` to the listener of each subscription that is open.
    ///
    /// @param failureHandler invoked with each listener that throws on its last notification and the exception; the delivery continues to the listeners of the
    ///                       remaining open subscriptions
    public void removeAll(Consumer<? super L> lastNotification, BiConsumer<? super L, RuntimeException> failureHandler) {
        // The loop iterates over the removed subscriptions, so a listener may close its own subscription, and a new subscription may be added, while the
        // listener is notified.
        for (Subscription subscription : subscriptions.removeAll()) {
            if (subscription != null && !subscription.isClosed()) {
                try {
                    lastNotification.accept(subscription.listener);
                } catch (RuntimeException e) {
                    failureHandler.accept(subscription.listener, e);
                }
            }
        }
    }

    /// The number of added subscriptions, closed ones not yet removed included.
    public int size() {
        return subscriptions.size();
    }

    /// One listener's subscription; closing it ends the notifications.
    private final class Subscription extends BaseIdempotentCloseable {
        private final TaskExecutor executor;
        private final L listener;

        private Subscription(TaskExecutor executor, L listener) {
            this.executor = checkNotNull(executor, "executor");
            this.listener = checkNotNull(listener, "listener");
        }

        @Override
        protected void doClose() {
            executor.tryExecute("unsubscribe", () -> subscriptions.remove(this));
        }
    }
}
