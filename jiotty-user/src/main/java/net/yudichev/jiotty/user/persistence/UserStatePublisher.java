package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.observer.SerialisedListeners;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolutionListener;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserRecordListener;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.failedFuture;

/// The subscriptions of a [UserPersistence] implementation, and the delivery of its committed changes to them.
///
/// Every method other than the [#subscribe] overloads must be called on the executor that they take.
final class UserStatePublisher {
    private static final Logger logger = LogManager.getLogger(UserStatePublisher.class);

    private static final String RECORD_LISTENER_KIND = "User-record";

    private final SerialisedListeners<UserStateListener> stateListeners = new SerialisedListeners<>();
    private final SerialisedListeners<UserRecordListener> recordListeners = new SerialisedListeners<>();
    /// Each identity subscription under the user its identity currently resolves to; one resolving to nobody is absent here.
    private final SetMultimap<String, IdentitySubscription> identitySubscriptionsByUserId = HashMultimap.create();
    private final SetMultimap<UserIdentity, IdentitySubscription> identitySubscriptionsByIdentity = HashMultimap.create();

    /// Subscribes `listener`, which first receives the image that `imageReader` reads, and then every change published after the read. A failure to read the
    /// image ends the subscription as a failure of `listener` does.
    ///
    /// This method may be called on any thread, and so may [Closeable#close()] on the subscription that it returns.
    ///
    /// @param executor the executor on which `imageReader` is called and `listener` receives the image
    public Closeable subscribe(TaskExecutor executor, UserStateListener listener, Supplier<? extends List<UserProfileWithDeletion>> imageReader) {
        return stateListeners.subscribe(executor,
                                        listener,
                                        stateListener -> stateListener.onImage(imageReader.get()),
                                        UserStatePublisher::endStateSubscription);
    }

    /// Subscribes `listener`, which first receives the image that `imageReader` reads, and then every record change, purge and removal published after the
    /// read. A failure to read the image ends the subscription as a failure of `listener` does.
    ///
    /// This method may be called on any thread, and so may [Closeable#close()] on the subscription that it returns.
    ///
    /// @param executor    the executor on which `imageReader` is called and `listener` receives the image
    /// @param earliestDay a record delivered to `listener` holds no activity day before this day
    /// @param imageReader must return every user's record, each holding no activity day before `earliestDay`
    public Closeable subscribe(TaskExecutor executor,
                               LocalDate earliestDay,
                               UserRecordListener listener,
                               Supplier<? extends List<UserRecord>> imageReader) {
        return recordListeners.subscribe(executor,
                                         new DayBoundedUserRecordListener(earliestDay, listener),
                                         recordListener -> recordListener.onImage(imageReader.get()),
                                         UserStatePublisher::endRecordSubscription);
    }

    /// Subscribes `listener`, which first receives the resolution that `resolutionReader` reads, and then every different resolution published after the
    /// read. A failure to read the resolution reaches [IdentityResolutionListener#onSubscriptionFailed] unlogged, because its message names the identity.
    ///
    /// This method may be called on any thread, and so may [Closeable#close()] on the subscription that it returns.
    ///
    /// @param executor the executor on which `resolutionReader` is called and `listener` receives the first resolution
    public IdentitySubscription subscribe(TaskExecutor executor,
                                          UserIdentity identity,
                                          IdentityResolutionListener listener,
                                          Supplier<IdentityResolution> resolutionReader) {
        var subscription = new IdentitySubscription(executor, identity, listener);
        executor.execute("subscribe to an identity", () -> activate(subscription, resolutionReader));
        return subscription;
    }

    private void activate(IdentitySubscription subscription, Supplier<IdentityResolution> resolutionReader) {
        if (subscription.isClosed()) {
            return;
        }
        IdentityResolution resolution;
        try {
            resolution = resolutionReader.get();
        } catch (RuntimeException e) {
            subscription.listener.onSubscriptionFailed(e);
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
        stateListeners.publish(listener -> listener.onChanged(user), UserStatePublisher::endStateSubscription);
        IdentityResolution resolution = IdentityResolution.createLinked(user.profile(), user.deletedAt().isPresent());
        deliver(identitySubscriptionsByUserId.get(user.profile().id()), resolution);
        for (UserIdentity identity : linkedIdentities) {
            deliver(identitySubscriptionsByIdentity.get(identity), resolution);
        }
        for (UserIdentity identity : unlinkedIdentities) {
            deliver(identitySubscriptionsByIdentity.get(identity), IdentityResolution.Absent.INSTANCE);
        }
    }

    /// Publishes a committed write that changed a user's record. `record` must be that user's whole record as the write left it.
    public void publishRecordChanged(UserRecord record) {
        recordListeners.publish(listener -> listener.onChanged(record), UserStatePublisher::endRecordSubscription);
    }

    /// Publishes a committed deletion of every user's activity days before `day`.
    public void publishDaysPurged(LocalDate day) {
        recordListeners.publish(listener -> listener.onDaysPurged(day), UserStatePublisher::endRecordSubscription);
    }

    /// Ends every record subscription: each open subscriber receives `failure` through [UserRecordListener#onSubscriptionFailed], and no record subscriber
    /// receives anything further.
    public void failRecordSubscriptions(Throwable failure) {
        recordListeners.removeAll(listener -> listener.onSubscriptionFailed(failure),
                                  (_, e) -> logOnSubscriptionFailedFailure(RECORD_LISTENER_KIND, e));
    }

    /// Publishes a committed hard delete of `userId`.
    ///
    /// @return a future that completes once every subscribed [UserRecordListener] and [UserStateListener] has applied the removal. It completes exceptionally
    ///         if a delivery to one of them throws, or if the future that one of them returns completes exceptionally.
    public CompletableFuture<Void> publishRemoved(String userId) {
        var acknowledgements = new ArrayList<CompletableFuture<?>>(recordListeners.size() + stateListeners.size());
        notifyRemoved(recordListeners, listener -> listener.onRemoved(userId), UserStatePublisher::endRecordSubscription, acknowledgements::add);
        notifyRemoved(stateListeners, listener -> listener.onRemoved(userId), UserStatePublisher::endStateSubscription, acknowledgements::add);
        deliver(identitySubscriptionsByUserId.get(userId), IdentityResolution.Absent.INSTANCE);
        return CompletableFuture.allOf(acknowledgements.toArray(new CompletableFuture<?>[0]));
    }

    /// Delivers a removal to each of `listeners`, and passes to `acknowledgementConsumer` the future that each listener returns. When a delivery throws,
    /// `acknowledgementConsumer` receives a failed future for that listener instead.
    ///
    /// @param <L>               the type of the listeners
    /// @param removal           must invoke the listener's [UserStateListener#onRemoved] or [UserRecordListener#onRemoved]
    /// @param subscriptionEnder invoked with a listener that threw and the exception it threw
    private static <L> void notifyRemoved(SerialisedListeners<L> listeners,
                                          Function<? super L, ? extends CompletableFuture<?>> removal,
                                          BiConsumer<? super L, RuntimeException> subscriptionEnder,
                                          Consumer<? super CompletableFuture<?>> acknowledgementConsumer) {
        listeners.publish(listener -> acknowledgementConsumer.accept(removal.apply(listener)),
                          (listener, e) -> {
                              acknowledgementConsumer.accept(failedFuture(e));
                              subscriptionEnder.accept(listener, e);
                          });
    }

    /// The subscribed identities a write to `userId` can change the resolution of: those resolving to the user, and those among `touchedIdentities` that are
    /// subscribed.
    public Set<UserIdentity> subscribedIdentities(String userId, Iterable<UserIdentity> touchedIdentities) {
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
    public void publishResolution(UserIdentity identity, IdentityResolution resolution) {
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

    private static void endRecordSubscription(UserRecordListener listener, RuntimeException failure) {
        endSubscription(RECORD_LISTENER_KIND, listener::onSubscriptionFailed, failure);
    }

    private static void endIdentitySubscription(IdentityResolutionListener listener, RuntimeException failure) {
        endSubscription("Identity", listener::onSubscriptionFailed, failure);
    }

    /// Logs the failure that ended a listener's subscription, and invokes `subscriptionFailureHandler` with it. The failure is a throw of the listener
    /// on a delivery, or a failed read of the image for a state or record subscription. A delivery runs after the write that it delivers has committed, so
    /// nothing the listener throws may reach that write.
    ///
    /// @param listenerKind               names the kind of listener in the log
    /// @param subscriptionFailureHandler the listener's [UserStateListener#onSubscriptionFailed], [UserRecordListener#onSubscriptionFailed] or
    ///                                   [IdentityResolutionListener#onSubscriptionFailed]
    private static void endSubscription(String listenerKind, Consumer<Throwable> subscriptionFailureHandler, RuntimeException failure) {
        logger.info("{} subscription ended by a failure", listenerKind, failure);
        try {
            subscriptionFailureHandler.accept(failure);
        } catch (RuntimeException e) {
            logOnSubscriptionFailedFailure(listenerKind, e);
        }
    }

    private static void logOnSubscriptionFailedFailure(String listenerKind, RuntimeException failure) {
        logger.info("{} listener failed on being told its subscription ended", listenerKind, failure);
    }

    /// A [UserRecordListener] that passes every delivery to `delegate`. It passes a changed record without the activity days before `earliestDay`, as
    /// [UserRecord#withoutDaysBefore] returns it, and every other delivery unchanged.
    private record DayBoundedUserRecordListener(LocalDate earliestDay, UserRecordListener delegate) implements UserRecordListener {
        private DayBoundedUserRecordListener {
            checkNotNull(earliestDay, "earliestDay");
            checkNotNull(delegate, "delegate");
        }

        @Override
        public void onImage(List<UserRecord> records) {
            delegate.onImage(records);
        }

        @Override
        public void onChanged(UserRecord record) {
            delegate.onChanged(record.withoutDaysBefore(earliestDay));
        }

        @Override
        public void onDaysPurged(LocalDate day) {
            delegate.onDaysPurged(day);
        }

        @Override
        public CompletableFuture<?> onRemoved(String userId) {
            return delegate.onRemoved(userId);
        }

        @Override
        public void onSubscriptionFailed(Throwable failure) {
            delegate.onSubscriptionFailed(failure);
        }
    }

    /// An [IdentityResolutionListener]'s subscription to one identity; closing it ends the deliveries.
    public final class IdentitySubscription extends BaseIdempotentCloseable {
        private final TaskExecutor executor;
        private final UserIdentity identity;
        private final IdentityResolutionListener listener;
        /// The resolution last delivered, or `null` before the subscription is activated.
        private @Nullable IdentityResolution resolution;

        private IdentitySubscription(TaskExecutor executor, UserIdentity identity, IdentityResolutionListener listener) {
            this.executor = checkNotNull(executor, "executor");
            this.identity = checkNotNull(identity, "identity");
            this.listener = checkNotNull(listener, "listener");
        }

        @Override
        protected void doClose() {
            executor.tryExecute("unsubscribe from an identity", () -> unindex(this));
        }
    }
}
