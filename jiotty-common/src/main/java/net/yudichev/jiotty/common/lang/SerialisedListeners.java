package net.yudichev.jiotty.common.lang;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;

/// Listeners that each receive an image of their owner's state from [#add], then every [#notify] after it; [#add], [#notify] and [#size] must be called on
/// one thread, or under one lock. [#newSubscription] may be called on any thread, and closing a subscription runs its removal through the executor passed to
/// the constructor.
public final class SerialisedListeners<L> {
    private static final Logger logger = LogManager.getLogger(SerialisedListeners.class);

    private final Executor removalExecutor;
    private final Set<Subscription> subscriptions = new HashSet<>();

    /// @param removalExecutor must run the removal of a closed subscription on the thread, or under the lock, that [#add] and [#notify] are called on
    public SerialisedListeners(Executor removalExecutor) {
        this.removalExecutor = checkNotNull(removalExecutor, "removalExecutor");
    }

    /// A subscription that receives nothing until [#add] is called with it.
    public Subscription newSubscription(L listener) {
        return new Subscription(listener);
    }

    /// Delivers the image to the subscription's listener, then adds the subscription to those [#notify] notifies, unless it is already closed.
    ///
    /// @param failureHandler told of a listener that throws on its image, which is then not added
    public void add(Subscription subscription, Consumer<? super L> imageDelivery, BiConsumer<? super L, RuntimeException> failureHandler) {
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

    /// Delivers a notification to the listener of each added subscription that is still open. It iterates a copy, so a listener may close its own subscription
    /// as it is notified.
    ///
    /// A listener whose executor rejects the notification with [RejectedExecutionException] stays subscribed, missing only that notification.
    ///
    /// @param failureHandler told of each listener that throws anything else, which is then removed, since it has missed this notification; the others are
    ///                       unaffected
    public void notify(Consumer<? super L> notification, BiConsumer<? super L, RuntimeException> failureHandler) {
        for (Subscription subscription : ImmutableList.copyOf(subscriptions)) {
            if (subscription.isClosed()) {
                subscriptions.remove(subscription);
            } else {
                try {
                    notification.accept(subscription.listener);
                } catch (RejectedExecutionException e) {
                    logger.debug("A listener missed a notification: {}", humanReadableMessageFormattable(e));
                } catch (RuntimeException e) {
                    subscriptions.remove(subscription);
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
    public final class Subscription extends BaseIdempotentCloseable {
        private final L listener;

        private Subscription(L listener) {
            this.listener = checkNotNull(listener, "listener");
        }

        @Override
        protected void doClose() {
            removalExecutor.execute(() -> subscriptions.remove(this));
        }
    }
}
