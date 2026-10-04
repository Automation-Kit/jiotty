package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.SerialisedListeners;
import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.Changed;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;
import net.yudichev.jiotty.user.persistence.UserStatePublisher.IdentitySubscription;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;

import static java.time.ZoneOffset.UTC;
import static net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest.IDENTITY;
import static net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest.OTHER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

class UserStatePublisherTest {
    private static final Instant CREATED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final UserProfile PROFILE = new UserProfile("u1", "user@example.com", Optional.of("Alex"), UTC, CREATED_AT, CREATED_AT);
    private static final UserProfileWithDeletion ACTIVE_USER = new UserProfileWithDeletion(PROFILE, Optional.empty());
    private static final UserProfileWithDeletion SOFT_DELETED_USER = new UserProfileWithDeletion(PROFILE, Optional.of(CREATED_AT));

    private final UserStatePublisher publisher = new UserStatePublisher(Runnable::run);

    @Test
    void aSubscriptionClosedBeforeItsImageArrivesReceivesNothing() {
        var stateListener = new RecordingUserStateListener();
        SerialisedListeners<UserStateListener>.Subscription subscription = publisher.newSubscription(stateListener);

        subscription.close();
        publisher.activate(subscription, List.of(ACTIVE_USER));
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.imageArrived()).isFalse();
    }

    @Test
    void anIdentitySubscriptionClosedBeforeItsFirstResolutionArrivesReceivesNothing() {
        var identityListener = new RecordingIdentityListener();
        IdentitySubscription subscription = publisher.newSubscription(IDENTITY, identityListener);

        subscription.close();
        publisher.activate(subscription, new IdentityResolution.Active(PROFILE));
        publisher.publishChanged(SOFT_DELETED_USER, List.of(IDENTITY), List.of());

        assertThat(identityListener.resolutions()).isEmpty();
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(IDENTITY))).isEmpty();
    }

    @Test
    void aListenerThatFailsOnItsImageIsToldAndReceivesNothingAfterIt() {
        var stateListener = new FailureRecordingStateListener() {
            @Override
            public void onImage(List<UserProfileWithDeletion> users) {
                throw new RuntimeException("image boom");
            }
        };

        publisher.activate(publisher.newSubscription(stateListener), List.of());
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("image boom");
        assertThat(stateListener.receivedChanges).isEmpty();
    }

    /// A listener that throws on a change has missed it, so it is told its subscription has ended and receives no change after it.
    @Test
    void aListenerThatFailsOnAChangeIsToldAndReceivesNothingAfterIt() {
        var stateListener = new FailureRecordingStateListener() {
            @Override
            public void onChanged(UserProfileWithDeletion user) {
                super.onChanged(user);
                throw new RuntimeException("change boom");
            }
        };
        publisher.activate(publisher.newSubscription(stateListener), List.of());

        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());
        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("change boom");
        assertThat(stateListener.receivedChanges).containsExactly(ACTIVE_USER);
    }

    /// The delivery runs after its write has committed, so a listener that throws again on being told its subscription ended reaches neither the write nor
    /// the other listeners.
    @Test
    void aListenerThatAlsoFailsOnBeingToldItsSubscriptionEndedLeavesTheOthersNotified() {
        var failingListener = new FailureRecordingStateListener() {
            @Override
            public void onChanged(UserProfileWithDeletion user) {
                throw new RuntimeException("change boom");
            }

            @Override
            public void onSubscriptionFailed(Throwable failure) {
                throw new RuntimeException("failure boom");
            }
        };
        var healthyListener = new RecordingUserStateListener();
        publisher.activate(publisher.newSubscription(failingListener), List.of());
        publisher.activate(publisher.newSubscription(healthyListener), List.of());

        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(healthyListener.changes()).containsExactly(new Changed(ACTIVE_USER));
    }

    @Test
    void aListenerThatFailsOnItsFirstResolutionIsToldAndReceivesNothingAfterIt() {
        var identityListener = new RecordingIdentityListener() {
            @Override
            public void onResolution(IdentityResolution resolution) {
                super.onResolution(resolution);
                throw new RuntimeException("resolution boom");
            }
        };

        publisher.activate(publisher.newSubscription(IDENTITY, identityListener), IdentityResolution.Absent.INSTANCE);
        publisher.publishChanged(ACTIVE_USER, List.of(IDENTITY), List.of());

        assertThat(identityListener.failures()).singleElement().extracting(Throwable::getMessage).isEqualTo("resolution boom");
        assertThat(identityListener.resolutions()).containsExactly(IdentityResolution.Absent.INSTANCE);
    }

    @Test
    void aListenerThatFailsOnALaterResolutionIsToldAndReceivesNothingAfterIt() {
        var identityListener = new RecordingIdentityListener() {
            @Override
            public void onResolution(IdentityResolution resolution) {
                super.onResolution(resolution);
                if (resolution instanceof IdentityResolution.SoftDeleted) {
                    throw new RuntimeException("resolution boom");
                }
            }
        };
        publisher.activate(publisher.newSubscription(IDENTITY, identityListener), new IdentityResolution.Active(PROFILE));

        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(identityListener.failures()).singleElement().extracting(Throwable::getMessage).isEqualTo("resolution boom");
        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE), new IdentityResolution.SoftDeleted(PROFILE));
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(IDENTITY))).isEmpty();
    }

    @Test
    void aRemovalAListenerThrowsOnFailsTheAcknowledgementAndEndsTheSubscription() {
        var stateListener = new FailureRecordingStateListener() {
            @Override
            public CompletionStage<?> onRemoved(String userId) {
                throw new RuntimeException("removal boom");
            }
        };
        publisher.activate(publisher.newSubscription(stateListener), List.of(ACTIVE_USER));

        assertThat(publisher.publishRemoved(PROFILE.id())).failsWithin(Duration.ZERO);
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("removal boom");
        assertThat(stateListener.receivedChanges).isEmpty();
    }

    @Test
    void aRemovalAListenersExecutorRejectsFailsTheAcknowledgementAndKeepsTheSubscription() {
        var stateListener = new FailureRecordingStateListener() {
            @Override
            public CompletionStage<?> onRemoved(String userId) {
                throw new RejectedExecutionException("queue full");
            }
        };
        publisher.activate(publisher.newSubscription(stateListener), List.of(ACTIVE_USER));

        assertThat(publisher.publishRemoved(PROFILE.id())).failsWithin(Duration.ZERO);
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).isEmpty();
        assertThat(stateListener.receivedChanges).containsExactly(ACTIVE_USER);
    }

    @Test
    void aRemovalWithNoSubscriberIsAcknowledgedAtOnce() {
        assertThat(publisher.publishRemoved(PROFILE.id())).succeedsWithin(Duration.ZERO);
    }

    /// Where the removal executor drops the removal a closing subscription hands it, as one that has shut down does, the next delivery drops the subscription
    /// itself.
    @Test
    void aClosedSubscriptionIsSkippedAndForgottenEvenWhenItsRemovalIsDropped() {
        var droppingPublisher = new UserStatePublisher(_ -> {});
        var stateListener = new RecordingUserStateListener();
        SerialisedListeners<UserStateListener>.Subscription stateSubscription = droppingPublisher.newSubscription(stateListener);
        droppingPublisher.activate(stateSubscription, List.of());
        var identityListener = new RecordingIdentityListener();
        IdentitySubscription identitySubscription = droppingPublisher.newSubscription(IDENTITY, identityListener);
        droppingPublisher.activate(identitySubscription, new IdentityResolution.Active(PROFILE));

        stateSubscription.close();
        identitySubscription.close();
        droppingPublisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(stateListener.changes()).isEmpty();
        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE));
        assertThat(droppingPublisher.subscribedIdentities(PROFILE.id(), List.of(IDENTITY))).isEmpty();
    }

    @Test
    void listsTheSubscribedIdentitiesAWriteToAUserCanChange() {
        publisher.activate(publisher.newSubscription(IDENTITY, new RecordingIdentityListener()), new IdentityResolution.Active(PROFILE));
        publisher.activate(publisher.newSubscription(OTHER_IDENTITY, new RecordingIdentityListener()), IdentityResolution.Absent.INSTANCE);
        var unsubscribedIdentity = new UserIdentity("apple.com", "uid-a");

        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(OTHER_IDENTITY, unsubscribedIdentity)))
                .containsExactlyInAnyOrder(IDENTITY, OTHER_IDENTITY);
    }

    @Test
    void deliversAChangeToEverySubscriberOfTheUser() {
        var stateListener = new RecordingUserStateListener();
        publisher.activate(publisher.newSubscription(stateListener), List.of());
        var identityListener = new RecordingIdentityListener();
        publisher.activate(publisher.newSubscription(IDENTITY, identityListener), new IdentityResolution.Active(PROFILE));

        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(stateListener.changes()).containsExactly(new Changed(SOFT_DELETED_USER));
        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE), new IdentityResolution.SoftDeleted(PROFILE));
    }

    /// The read-back after a commit that reported a failure delivers what the database holds, which only a subscriber holding a different answer needs.
    @Test
    void aResolutionReadBackIsDeliveredOnlyWhereItDiffers() {
        var identityListener = new RecordingIdentityListener();
        publisher.activate(publisher.newSubscription(IDENTITY, identityListener), new IdentityResolution.Active(PROFILE));

        publisher.publishResolution(IDENTITY, new IdentityResolution.Active(PROFILE));
        publisher.publishResolution(IDENTITY, IdentityResolution.Absent.INSTANCE);

        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE), IdentityResolution.Absent.INSTANCE);
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of())).isEmpty();
    }

    /// Records the changes and the subscription failure a state listener receives, synchronously.
    private static class FailureRecordingStateListener extends RecordingUserStateListener {
        final List<UserProfileWithDeletion> receivedChanges = new ArrayList<>();
        final List<Throwable> failures = new ArrayList<>();

        @Override
        public void onChanged(UserProfileWithDeletion user) {
            receivedChanges.add(user);
        }

        @Override
        public void onSubscriptionFailed(Throwable failure) {
            failures.add(failure);
        }
    }
}
