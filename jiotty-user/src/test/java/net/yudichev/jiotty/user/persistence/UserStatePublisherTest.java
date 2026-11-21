package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.ImmutableSetMultimap;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.Changed;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolutionListener;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserRecordListener;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserStateListener;
import net.yudichev.jiotty.user.persistence.UserStatePublisher.IdentitySubscription;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.async.EvenMoreExecutors.directExecutor;
import static net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest.IDENTITY;
import static net.yudichev.jiotty.user.persistence.BaseUserChangeContractTest.OTHER_IDENTITY;
import static org.assertj.core.api.Assertions.assertThat;

class UserStatePublisherTest {
    private static final Instant CREATED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final UserProfile PROFILE = new UserProfile("u1", "user@example.com", Optional.of("Alex"), UTC, CREATED_AT, CREATED_AT);
    private static final UserProfileWithDeletion ACTIVE_USER = new UserProfileWithDeletion(PROFILE, Optional.empty());
    private static final UserProfileWithDeletion SOFT_DELETED_USER = new UserProfileWithDeletion(PROFILE, Optional.of(CREATED_AT));
    private static final LocalDate DAY = LocalDate.ofInstant(CREATED_AT, UTC);
    private static final UserRecord RECORD = createRecordWithDays(DAY);

    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("persistence");
    private final UserStatePublisher publisher = new UserStatePublisher();

    @Test
    void aSubscriptionClosedBeforeItsImageArrivesReceivesNothing() {
        var stateListener = new RecordingUserStateListener();
        Closeable subscription = publisher.subscribe(executor, stateListener, () -> List.of(ACTIVE_USER));

        subscription.close();
        clock.tick();
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.imageArrived()).isFalse();
    }

    @Test
    void anIdentitySubscriptionClosedBeforeItsFirstResolutionArrivesReceivesNothing() {
        var identityListener = new RecordingIdentityListener();
        IdentitySubscription subscription = publisher.subscribe(executor, IDENTITY, identityListener, () -> new IdentityResolution.Active(PROFILE));

        subscription.close();
        clock.tick();
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

        subscribe(stateListener, List.of());
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("image boom");
        assertThat(stateListener.receivedChanges).isEmpty();
    }

    @Test
    void aStateSubscriptionWhoseImageCannotBeReadReceivesTheFailureAndNothingAfterIt() {
        var stateListener = new FailureRecordingStateListener();

        publisher.subscribe(directExecutor(), stateListener, () -> {throw new RuntimeException("read boom");});
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("read boom");
        assertThat(stateListener.receivedChanges).isEmpty();
    }

    /// A listener that throws on a change may have missed the change, so the publisher invokes the listener's [UserStateListener#onSubscriptionFailed] and
    /// delivers no later change to the listener.
    @Test
    void aListenerThatFailsOnAChangeIsToldAndReceivesNothingAfterIt() {
        var stateListener = new FailureRecordingStateListener() {
            @Override
            public void onChanged(UserProfileWithDeletion user) {
                super.onChanged(user);
                throw new RuntimeException("change boom");
            }
        };
        subscribe(stateListener, List.of());

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
        subscribe(failingListener, List.of());
        subscribe(healthyListener, List.of());

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

        subscribe(IDENTITY, identityListener, IdentityResolution.Absent.INSTANCE);
        publisher.publishChanged(ACTIVE_USER, List.of(IDENTITY), List.of());

        assertThat(identityListener.failures()).singleElement().extracting(Throwable::getMessage).isEqualTo("resolution boom");
        assertThat(identityListener.resolutions()).containsExactly(IdentityResolution.Absent.INSTANCE);
    }

    /// The failure of the resolution read names the identity, so the publisher passes it to the listener and logs nothing.
    @Test
    void anIdentitySubscriptionWhoseResolutionCannotBeReadReceivesTheFailureAndNothingIsLogged() {
        var identityListener = new RecordingIdentityListener();
        var readFailure = new RuntimeException("Failed to resolve user for identity " + IDENTITY);

        try (LogCapture log = LogCapture.capture(UserStatePublisher.class, Level.DEBUG)) {
            publisher.subscribe(directExecutor(), IDENTITY, identityListener, () -> {throw readFailure;});

            assertThat(log.lines()).isEmpty();
        }
        publisher.publishChanged(ACTIVE_USER, List.of(IDENTITY), List.of());

        assertThat(identityListener.failures()).containsExactly(readFailure);
        assertThat(identityListener.resolutions()).isEmpty();
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(IDENTITY))).isEmpty();
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
        subscribe(IDENTITY, identityListener, new IdentityResolution.Active(PROFILE));

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
            public CompletableFuture<?> onRemoved(String userId) {
                throw new RuntimeException("removal boom");
            }
        };
        subscribe(stateListener, List.of(ACTIVE_USER));

        assertThat(publisher.publishRemoved(PROFILE.id())).failsWithin(Duration.ZERO);
        publisher.publishChanged(ACTIVE_USER, List.of(), List.of());

        assertThat(stateListener.failures).singleElement().extracting(Throwable::getMessage).isEqualTo("removal boom");
        assertThat(stateListener.receivedChanges).isEmpty();
    }

    @Test
    void aRemovalWithNoSubscriberIsAcknowledgedAtOnce() {
        assertThat(publisher.publishRemoved(PROFILE.id())).succeedsWithin(Duration.ZERO);
    }

    /// The next delivery skips and drops a closed subscription itself when the executor drops the removal that the closing queued, as an executor that has
    /// shut down does.
    @Test
    void aClosedSubscriptionIsSkippedAndForgottenEvenWhenItsRemovalIsDropped() {
        var droppingExecutor = new TryExecuteDroppingExecutor();
        var stateListener = new RecordingUserStateListener();
        Closeable stateSubscription = publisher.subscribe(droppingExecutor, stateListener, List::of);
        var identityListener = new RecordingIdentityListener();
        IdentitySubscription identitySubscription =
                publisher.subscribe(droppingExecutor, IDENTITY, identityListener, () -> new IdentityResolution.Active(PROFILE));

        stateSubscription.close();
        identitySubscription.close();
        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(stateListener.changes()).isEmpty();
        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE));
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(IDENTITY))).isEmpty();
    }

    @Test
    void listsTheSubscribedIdentitiesAWriteToAUserCanChange() {
        subscribe(IDENTITY, new RecordingIdentityListener(), new IdentityResolution.Active(PROFILE));
        subscribe(OTHER_IDENTITY, new RecordingIdentityListener(), IdentityResolution.Absent.INSTANCE);
        var unsubscribedIdentity = new UserIdentity("apple.com", "uid-a");

        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of(OTHER_IDENTITY, unsubscribedIdentity)))
                .containsExactlyInAnyOrder(IDENTITY, OTHER_IDENTITY);
    }

    @Test
    void deliversAChangeToEverySubscriberOfTheUser() {
        var stateListener = new RecordingUserStateListener();
        subscribe(stateListener, List.of());
        var identityListener = new RecordingIdentityListener();
        subscribe(IDENTITY, identityListener, new IdentityResolution.Active(PROFILE));

        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(stateListener.changes()).containsExactly(new Changed(SOFT_DELETED_USER));
        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE), new IdentityResolution.SoftDeleted(PROFILE));
    }

    /// The read-back after a commit that reported a failure delivers what the database holds, which only a subscriber holding a different answer needs.
    @Test
    void aResolutionReadBackIsDeliveredOnlyWhereItDiffers() {
        var identityListener = new RecordingIdentityListener();
        subscribe(IDENTITY, identityListener, new IdentityResolution.Active(PROFILE));

        publisher.publishResolution(IDENTITY, new IdentityResolution.Active(PROFILE));
        publisher.publishResolution(IDENTITY, IdentityResolution.Absent.INSTANCE);

        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(PROFILE), IdentityResolution.Absent.INSTANCE);
        assertThat(publisher.subscribedIdentities(PROFILE.id(), List.of())).isEmpty();
    }

    @Test
    void deliversRecordChangesPurgesAndRemovalsToARecordSubscriberAfterItsImage() {
        var recordListener = new RecordingUserRecordListener();
        subscribe(DAY, recordListener, List.of(RECORD));
        UserRecord changedRecord = createRecordWithDays(DAY, DAY.plusDays(1));

        publisher.publishRecordChanged(changedRecord);
        publisher.publishDaysPurged(DAY);
        publisher.publishRemoved(PROFILE.id());

        assertThat(recordListener.image()).isCompletedWithValue(List.of(RECORD));
        assertThat(recordListener.changes()).containsExactly(new RecordingUserRecordListener.Changed(changedRecord),
                                                             new RecordingUserRecordListener.DaysPurged(DAY),
                                                             new RecordingUserRecordListener.Removed(PROFILE.id()));
    }

    @Test
    void aChangedRecordReachesEachSubscriberCutAtItsOwnEarliestDay() {
        var earlierListener = new RecordingUserRecordListener();
        var laterListener = new RecordingUserRecordListener();
        subscribe(DAY.minusDays(1), earlierListener, List.of());
        subscribe(DAY.plusDays(1), laterListener, List.of());
        UserRecord changedRecord = createRecordWithDays(DAY.minusDays(1), DAY, DAY.plusDays(1));

        publisher.publishRecordChanged(changedRecord);

        assertThat(earlierListener.changes()).containsExactly(new RecordingUserRecordListener.Changed(changedRecord));
        assertThat(laterListener.changes()).containsExactly(new RecordingUserRecordListener.Changed(createRecordWithDays(DAY.plusDays(1))));
    }

    @Test
    void aRemovalIsAcknowledgedOnlyOnceBothAStateAndARecordSubscriberHaveAppliedIt() {
        var stateRemovalCompletion = new CompletableFuture<Void>();
        var recordRemovalCompletion = new CompletableFuture<Void>();
        subscribe(new RecordingUserStateListener() {
            @Override
            public CompletableFuture<?> onRemoved(String userId) {
                return stateRemovalCompletion;
            }
        }, List.of(ACTIVE_USER));
        subscribe(DAY, new RecordingUserRecordListener() {
            @Override
            public CompletableFuture<?> onRemoved(String userId) {
                return recordRemovalCompletion;
            }
        }, List.of(RECORD));

        CompletableFuture<Void> acknowledgement = publisher.publishRemoved(PROFILE.id());

        assertThat(acknowledgement).isNotDone();
        stateRemovalCompletion.complete(null);
        assertThat(acknowledgement).isNotDone();
        recordRemovalCompletion.complete(null);
        assertThat(acknowledgement).succeedsWithin(Duration.ZERO);
    }

    @Test
    void aRemovalARecordListenerThrowsOnFailsTheAcknowledgementAndEndsTheSubscription() {
        var recordListener = new RecordingUserRecordListener() {
            @Override
            public CompletableFuture<?> onRemoved(String userId) {
                throw new RuntimeException("removal boom");
            }
        };
        subscribe(DAY, recordListener, List.of(RECORD));

        assertThat(publisher.publishRemoved(PROFILE.id())).failsWithin(Duration.ZERO);
        publisher.publishRecordChanged(RECORD);

        assertThat(recordListener.failure()).succeedsWithin(Duration.ZERO).extracting(Throwable::getMessage).isEqualTo("removal boom");
        assertThat(recordListener.changes()).isEmpty();
    }

    @Test
    void failingTheRecordSubscriptionsTellsEachSubscriberAndDeliversNothingAfterwards() {
        var recordListener = new RecordingUserRecordListener();
        var stateListener = new RecordingUserStateListener();
        subscribe(DAY, recordListener, List.of(RECORD));
        subscribe(stateListener, List.of(ACTIVE_USER));
        var failure = new RuntimeException("read-back boom");

        publisher.failRecordSubscriptions(failure);
        publisher.publishRecordChanged(RECORD);
        publisher.publishChanged(SOFT_DELETED_USER, List.of(), List.of());

        assertThat(recordListener.failure()).isCompletedWithValue(failure);
        assertThat(recordListener.changes()).isEmpty();
        assertThat(stateListener.changes()).containsExactly(new Changed(SOFT_DELETED_USER));
    }

    @Test
    void aRecordListenerThatThrowsFromOnSubscriptionFailedIsLoggedAtInfoAndTheOthersStillReceiveTheFailure() {
        var listenerFailure = new RuntimeException("failure boom");
        subscribe(DAY, new RecordingUserRecordListener() {
            @Override
            public void onSubscriptionFailed(Throwable failure) {
                throw listenerFailure;
            }
        }, List.of(RECORD));
        var healthyListener = new RecordingUserRecordListener();
        subscribe(DAY, healthyListener, List.of(RECORD));
        var failure = new RuntimeException("read-back boom");

        try (LogCapture log = LogCapture.capture(UserStatePublisher.class, Level.INFO)) {
            publisher.failRecordSubscriptions(failure);

            assertThat(log.lines()).singleElement()
                                   .satisfies(line -> {
                                       assertThat(line.level()).isEqualTo(Level.INFO);
                                       assertThat(line.thrown()).containsExactly(listenerFailure.toString());
                                   });
        }
        assertThat(healthyListener.failure()).isCompletedWithValue(failure);
    }

    private void subscribe(UserStateListener listener, List<UserProfileWithDeletion> image) {
        publisher.subscribe(directExecutor(), listener, () -> image);
    }

    private void subscribe(UserIdentity identity, IdentityResolutionListener listener, IdentityResolution resolution) {
        publisher.subscribe(directExecutor(), identity, listener, () -> resolution);
    }

    private void subscribe(LocalDate earliestDay, UserRecordListener listener, List<UserRecord> image) {
        publisher.subscribe(directExecutor(), earliestDay, listener, () -> image);
    }

    private static UserRecord createRecordWithDays(LocalDate... days) {
        return new UserRecord(PROFILE.id(), ImmutableSetMultimap.<String, LocalDate>builder().putAll("CHARGE_STARTED", days).build(), false, Map.of());
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

    /// Runs every task in place, and drops every task given to [#tryExecute], as an executor that has shut down does.
    private static final class TryExecuteDroppingExecutor implements TaskExecutor {
        @Override
        public <T> CompletableFuture<T> submit(Callable<? extends T> task) {
            try {
                return completedFuture(task.call());
            } catch (Exception e) {
                return failedFuture(e);
            }
        }

        @Override
        public void execute(String taskName, Runnable command) {
            command.run();
        }

        @Override
        public boolean tryExecute(String taskName, Runnable command) {
            return false;
        }
    }
}
