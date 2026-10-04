package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.SerialisedListeners;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolutionListener;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.allOf;

/// The subscriptions of a [UserPersistence] implementation, and the delivery of its committed changes to them. Every method but
/// [#newSubscription(UserStateListener)] and [#newSubscription(UserIdentity, IdentityResolutionListener)], which only create one, must be called on the
/// implementation's one thread, or under its one lock; closing a subscription runs its removal there, through the executor passed to the constructor.
public final class UserStatePublisher {
    private static final Logger logger = LogManager.getLogger(UserStatePublisher.class);

    private final Executor removalExecutor;
    private final SerialisedListeners<UserStateListener> stateListeners;
    /// Each identity subscription under the user its identity currently resolves to; one resolving to nobody is absent here.
    private final SetMultimap<String, IdentitySubscription> identitySubscriptionsByUserId = HashMultimap.create();
    private final SetMultimap<UserIdentity, IdentitySubscription> identitySubscriptionsByIdentity = HashMultimap.create();

    /// @param removalExecutor must run the removal of a closed subscription on the thread, or under the lock, that every other method here is called on
    public UserStatePublisher(Executor removalExecutor) {
        this.removalExecutor = checkNotNull(removalExecutor, "removalExecutor");
        stateListeners = new SerialisedListeners<>(removalExecutor);
    }

    /// A subscription that delivers nothing until [#activate(SerialisedListeners.Subscription, List)] is called with its image.
    public SerialisedListeners<UserStateListener>.Subscription newSubscription(UserStateListener listener) {
        return stateListeners.newSubscription(listener);
    }

    /// A subscription that delivers nothing until [#activate(IdentitySubscription, IdentityResolution)] is called with its first resolution.
    public IdentitySubscription newSubscription(UserIdentity identity, IdentityResolutionListener listener) {
        return new IdentitySubscription(identity, listener);
    }

    /// Delivers `image` to the subscription and then every change published after it, unless the subscription is already closed.
    public void activate(SerialisedListeners<UserStateListener>.Subscription subscription, List<UserProfileWithDeletion> image) {
        stateListeners.add(subscription, listener -> listener.onImage(image), UserStatePublisher::endStateSubscription);
    }

    /// Delivers `resolution` to the subscription and then every different resolution published after it, unless the subscription is already closed.
    public void activate(IdentitySubscription subscription, IdentityResolution resolution) {
        if (subscription.isClosed()) {
            return;
        }
        try {
            subscription.listener.onResolution(resolution);
        } catch (RuntimeException e) {
            endIdentitySubscription(subscription.listener, e);
            return;
        }
        subscription.resolution = resolution;
        identitySubscriptionsByIdentity.put(subscription.identity, subscription);
        reindexByUser(subscription, null);
    }

    /// Publishes a committed write that left a row for `user`.
    ///
    /// @param linkedIdentities   identities the write made resolve to `user`
    /// @param unlinkedIdentities identities the write made resolve to nobody
    public void publishChanged(UserProfileWithDeletion user, Collection<UserIdentity> linkedIdentities, Collection<UserIdentity> unlinkedIdentities) {
        stateListeners.notify(listener -> listener.onChanged(user), UserStatePublisher::endStateSubscription);
        IdentityResolution resolution = IdentityResolution.createLinked(user.profile(), user.deletedAt().isPresent());
        deliver(identitySubscriptionsByUserId.get(user.profile().id()), resolution);
        for (UserIdentity identity : linkedIdentities) {
            deliver(identitySubscriptionsByIdentity.get(identity), resolution);
        }
        for (UserIdentity identity : unlinkedIdentities) {
            deliver(identitySubscriptionsByIdentity.get(identity), IdentityResolution.Absent.INSTANCE);
        }
    }

    /// Publishes a committed hard delete of `userId`.
    ///
    /// @return completes once every state subscriber has applied the removal
    public CompletableFuture<Void> publishRemoved(String userId) {
        var acknowledgements = ImmutableList.<CompletableFuture<?>>builderWithExpectedSize(stateListeners.size());
        stateListeners.notify(listener -> {
            try {
                acknowledgements.add(listener.onRemoved(userId).toCompletableFuture());
            } catch (RejectedExecutionException e) {
                // Fails the hard delete, which is retried, rather than reporting the removal applied by a listener that never received it.
                acknowledgements.add(failedFuture(e));
                throw e;
            }
        }, (listener, e) -> {
            acknowledgements.add(failedFuture(e));
            endStateSubscription(listener, e);
        });
        deliver(identitySubscriptionsByUserId.get(userId), IdentityResolution.Absent.INSTANCE);
        return allOf(acknowledgements.build());
    }

    /// The subscribed identities a write to `userId` can change the resolution of: those resolving to the user, and those among `touchedIdentities` that are
    /// subscribed.
    Set<UserIdentity> subscribedIdentities(String userId, Iterable<UserIdentity> touchedIdentities) {
        var identities = ImmutableSet.<UserIdentity>builder();
        identitySubscriptionsByUserId.get(userId).forEach(subscription -> identities.add(subscription.identity));
        for (UserIdentity identity : touchedIdentities) {
            if (identitySubscriptionsByIdentity.containsKey(identity)) {
                identities.add(identity);
            }
        }
        return identities.build();
    }

    /// Delivers `resolution` to the subscriptions to `identity` whose last resolution differs.
    void publishResolution(UserIdentity identity, IdentityResolution resolution) {
        deliver(identitySubscriptionsByIdentity.get(identity), resolution);
    }

    /// Delivers `resolution` to each of `subscriptions` whose last one differs. Iterates a copy, because re-indexing a subscription edits the set it came from.
    private void deliver(Set<IdentitySubscription> subscriptions, IdentityResolution resolution) {
        for (IdentitySubscription subscription : ImmutableList.copyOf(subscriptions)) {
            if (subscription.isClosed()) {
                unindex(subscription);
            } else if (!resolution.equals(subscription.resolution)) {
                IdentityResolution previousResolution = subscription.resolution;
                subscription.resolution = resolution;
                reindexByUser(subscription, previousResolution);
                try {
                    subscription.listener.onResolution(resolution);
                } catch (RuntimeException e) {
                    unindex(subscription);
                    endIdentitySubscription(subscription.listener, e);
                }
            }
        }
    }

    /// Moves the subscription from the user `previousResolution` named to the one its resolution names now, when they differ.
    ///
    /// @param previousResolution `null` when the subscription was not yet indexed
    private void reindexByUser(IdentitySubscription subscription, @Nullable IdentityResolution previousResolution) {
        String previousUserId = resolvedUserId(previousResolution);
        String userId = resolvedUserId(subscription.resolution);
        if (Objects.equals(previousUserId, userId)) {
            return;
        }
        if (previousUserId != null) {
            identitySubscriptionsByUserId.remove(previousUserId, subscription);
        }
        if (userId != null) {
            identitySubscriptionsByUserId.put(userId, subscription);
        }
    }

    private void unindex(IdentitySubscription subscription) {
        String userId = resolvedUserId(subscription.resolution);
        if (userId != null) {
            identitySubscriptionsByUserId.remove(userId, subscription);
        }
        identitySubscriptionsByIdentity.remove(subscription.identity, subscription);
    }

    /// @param resolution `null` before the subscription holding it is activated
    /// @return `null` when nobody is resolved to
    private static @Nullable String resolvedUserId(@Nullable IdentityResolution resolution) {
        return switch (resolution) {
            case IdentityResolution.Active(UserProfile profile) -> profile.id();
            case IdentityResolution.SoftDeleted(UserProfile profile) -> profile.id();
            case IdentityResolution.Absent _ -> null;
            case null -> null;
        };
    }

    private static void endStateSubscription(UserStateListener listener, RuntimeException failure) {
        endSubscription("User-state", listener::onSubscriptionFailed, failure);
    }

    private static void endIdentitySubscription(IdentityResolutionListener listener, RuntimeException failure) {
        endSubscription("Identity", listener::onSubscriptionFailed, failure);
    }

    /// Tells a listener that threw on a delivery, and so is no longer subscribed, that its subscription has ended. A delivery runs after the write it
    /// delivers has committed, so nothing the listener throws may reach that write.
    ///
    /// @param listenerKind               names the kind of listener in the log
    /// @param subscriptionFailureHandler the listener's [UserStateListener#onSubscriptionFailed] or [IdentityResolutionListener#onSubscriptionFailed]
    private static void endSubscription(String listenerKind, Consumer<Throwable> subscriptionFailureHandler, RuntimeException failure) {
        logger.info("{} listener failed on a delivery, so it is unsubscribed", listenerKind, failure);
        try {
            subscriptionFailureHandler.accept(failure);
        } catch (RuntimeException e) {
            logger.info("{} listener failed on being told its subscription ended", listenerKind, e);
        }
    }

    /// An [IdentityResolutionListener]'s subscription to one identity; closing it ends the deliveries.
    public final class IdentitySubscription extends BaseIdempotentCloseable {
        private final UserIdentity identity;
        private final IdentityResolutionListener listener;
        /// The resolution last delivered, or `null` before the subscription is activated.
        private @Nullable IdentityResolution resolution;

        private IdentitySubscription(UserIdentity identity, IdentityResolutionListener listener) {
            this.identity = checkNotNull(identity, "identity");
            this.listener = checkNotNull(listener, "listener");
        }

        @Override
        protected void doClose() {
            removalExecutor.execute(() -> unindex(this));
        }
    }
}
