package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.Changed;
import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.Removed;
import net.yudichev.jiotty.user.persistence.RecordingUserStateListener.StateEvent;
import net.yudichev.jiotty.user.persistence.UserPersistence.IdentityResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// The subscription contract every [UserPersistence] honours, run against each implementation by a subclass. It is shared rather than written twice because a
/// test double that announces different changes from the real store makes every test built on that double prove something production does not do.
public abstract class BaseUserChangeContractTest {
    protected static final UserIdentity IDENTITY = new UserIdentity("firebase", "uid-1");
    protected static final UserIdentity OTHER_IDENTITY = new UserIdentity("google.com", "uid-g");
    /// Where every implementation's clock starts, so a subclass need not invent one.
    protected static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    /// Any interval a grace period could plausibly span; the point is only that the restore lands at a different instant from the soft delete.
    private static final Duration RESTORE_DELAY = Duration.ofDays(3);
    /// The clock both implementations are built on, so a test can move time and assert an exact stamp rather than an inequality that holds either way.
    protected final ProgrammableClock clock = new ProgrammableClock();

    private RecordingUserStateListener stateListener;

    /// The started store under test, built afresh for each test by the subclass on [#clock].
    protected abstract UserPersistence userPersistence();

    @BeforeEach
    final void setUpContract() {
        stateListener = new RecordingUserStateListener();
    }

    @Test
    void deliversEveryUserAsTheImageBeforeAnyChange() {
        UserProfile activeProfile = createUser(IDENTITY, "Alex");
        UserProfile softDeletedProfile = createUser(OTHER_IDENTITY, "Sam", "sam@example.com");
        await(userPersistence().softDelete(softDeletedProfile.id()));

        subscribe();

        assertThat(stateListener.image()).succeedsWithin(DELIVERY_TIMEOUT).asInstanceOf(list(UserProfileWithDeletion.class)).satisfiesExactlyInAnyOrder(
                user -> {
                    assertThat(user.profile()).isEqualTo(activeProfile);
                    assertThat(user.deletedAt()).isEmpty();
                },
                user -> {
                    assertThat(user.profile().id()).isEqualTo(softDeletedProfile.id());
                    assertThat(user.deletedAt()).isPresent();
                });
    }

    /// Subscribing and reading the image are one step inside [UserPersistence#subscribe(UserPersistence.UserStateListener)], so a change committed straight
    /// after the subscribe call is a delta after the image, never a row inside it that then arrives again.
    @Test
    void aChangeCommittedRightAfterSubscribingArrivesAfterTheImage() {
        subscribe();

        UserProfile createdProfile = createUser(IDENTITY, "Alex");

        assertThat(stateListener.image()).succeedsWithin(DELIVERY_TIMEOUT).asInstanceOf(list(UserProfileWithDeletion.class)).isEmpty();
        assertThat(stateListener.changes()).singleElement().isEqualTo(new Changed(new UserProfileWithDeletion(createdProfile, Optional.empty())));
    }

    @Test
    void announcesEveryMutationThatChangedSomething() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateProfile(createdProfile.id(), profileInput("Alexandra")));
        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        await(userPersistence().softDelete(createdProfile.id()));
        await(userPersistence().restore(createdProfile.id()));
        await(userPersistence().hardDelete(createdProfile.id()));

        assertThat(stateListener.changes()).satisfiesExactly(profileUpdate -> assertChanged(profileUpdate, user -> {
                                                                 assertThat(user.profile().displayName()).hasValue("Alexandra");
                                                                 assertThat(user.deletedAt()).isEmpty();
                                                             }),
                                                             identityUpdate -> assertChanged(identityUpdate, user -> assertThat(user.deletedAt()).isEmpty()),
                                                             softDelete -> assertChanged(softDelete, user -> assertThat(user.deletedAt()).isPresent()),
                                                             restore -> assertChanged(restore, user -> assertThat(user.deletedAt()).isEmpty()),
                                                             hardDelete -> assertThat(hardDelete).isEqualTo(new Removed(createdProfile.id())));
    }

    /// A restore clears the deletion mark and stamps the update time with the restore instant, so a subscriber sees the row move rather than keep the instant
    /// the soft delete left on it.
    @Test
    void aRestoreStampsTheUpdateTime() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().softDelete(createdProfile.id()));
        Instant softDeletedAt = profileNow(createdProfile.id()).updatedAt();
        Instant restoredAt = softDeletedAt.plus(RESTORE_DELAY);
        clock.setTime(restoredAt);
        subscribe();

        await(userPersistence().restore(createdProfile.id()));

        assertThat(stateListener.changes()).singleElement()
                                           .isInstanceOfSatisfying(Changed.class,
                                                                   restore -> assertThat(restore.user().profile().updatedAt()).isEqualTo(restoredAt));
    }

    /// A repeated soft delete or restore announces the state it found, so a retry repeats an announcement that a commit reporting a failure left undelivered.
    @ParameterizedTest
    @MethodSource
    void reAnnouncesWhenASoftDeleteOrRestoreFindsItsWorkAlreadyDone(RepeatableCall call) {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(call.apply(userPersistence(), createdProfile.id()));
        subscribe();

        await(call.apply(userPersistence(), createdProfile.id()));

        assertThat(stateListener.changes()).singleElement().isEqualTo(new Changed(userNow(createdProfile.id())));
    }

    static Stream<Arguments> reAnnouncesWhenASoftDeleteOrRestoreFindsItsWorkAlreadyDone() {
        return Stream.of(arguments(named("soft delete", (RepeatableCall) UserPersistence::softDelete)),
                         arguments(named("restore", (RepeatableCall) UserPersistence::restore)));
    }

    /// A repeated hard delete announces the removal again, so a retry repeats a removal that a commit reporting a failure left undelivered.
    @Test
    void reAnnouncesTheRemovalWhenAHardDeleteFindsTheUserAlreadyGone() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().hardDelete(createdProfile.id()));
        subscribe();

        await(userPersistence().hardDelete(createdProfile.id()));

        assertThat(stateListener.changes()).singleElement().isEqualTo(new Removed(createdProfile.id()));
    }

    @Test
    void staysSilentWhenIdentitiesAreReplacedByTheSameOnes() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY)));

        assertThat(stateListener.changes()).isEmpty();
    }

    /// The store resolves each provider on its own, so the order a caller lists identities in is not a change. A sign-in provider is free to return them in
    /// any order, and re-announcing on every sign-in would make the stream useless.
    @Test
    void staysSilentWhenTheSameIdentitiesArriveInADifferentOrder() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        subscribe();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(OTHER_IDENTITY, IDENTITY)));

        assertThat(stateListener.changes()).isEmpty();
    }

    @Test
    void announcesAnIdentityThatWasAdded() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));

        assertThat(stateListener.changes()).singleElement().isEqualTo(new Changed(new UserProfileWithDeletion(createdProfile, Optional.empty())));
    }

    @Test
    void announcesAnIdentityThatWasRemoved() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        subscribe();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY)));

        assertThat(stateListener.changes()).singleElement().isEqualTo(new Changed(new UserProfileWithDeletion(createdProfile, Optional.empty())));
    }

    @Test
    void staysSilentWhenGetOrCreateResolvesAnExistingUser() {
        createUser(IDENTITY, "Alex");
        subscribe();

        createUser(IDENTITY, "Alex");

        assertThat(stateListener.changes()).isEmpty();
    }

    /// The mutation has already committed by the time listeners run, so one that throws must reach neither the caller nor the other listeners.
    @Test
    void containsAFailingSubscriberSoTheMutationStillSucceeds() {
        var failingListener = new RecordingUserStateListener() {
            @Override
            public void onChanged(UserProfileWithDeletion user) {
                throw new RuntimeException("listener boom");
            }
        };
        userPersistence().subscribe(failingListener);
        subscribe();

        UserProfile createdProfile = createUser(IDENTITY, "Alex");

        assertThat(stateListener.changes()).singleElement().isEqualTo(new Changed(new UserProfileWithDeletion(createdProfile, Optional.empty())));
    }

    @Test
    void stopsNotifyingAnUnsubscribedListener() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        Closeable subscription = subscribe();
        stateListener.awaitImage();

        subscription.close();
        await(userPersistence().softDelete(createdProfile.id()));

        assertThat(stateListener.changes()).isEmpty();
    }

    @Test
    void hardDeleteCompletesOnceEverySubscriberHasAppliedTheRemoval() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        var removalApplied = new CompletableFuture<Void>();
        var removalDelivered = new CompletableFuture<String>();
        userPersistence().subscribe(new RecordingUserStateListener() {
            @Override
            public CompletionStage<?> onRemoved(String userId) {
                removalDelivered.complete(userId);
                return removalApplied;
            }
        });
        subscribe();

        CompletableFuture<Void> hardDelete = userPersistence().hardDelete(createdProfile.id());

        assertThat(removalDelivered).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(createdProfile.id());
        assertThat(hardDelete).isNotDone();
        removalApplied.complete(null);
        assertThat(hardDelete).succeedsWithin(DELIVERY_TIMEOUT);
    }

    @Test
    void hardDeleteFailsWhenASubscriberFailsToApplyTheRemoval() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        userPersistence().subscribe(new RecordingUserStateListener() {
            @Override
            public CompletionStage<?> onRemoved(String userId) {
                return failedFuture(new RuntimeException("could not apply the removal"));
            }
        });

        assertThat(userPersistence().hardDelete(createdProfile.id())).failsWithin(DELIVERY_TIMEOUT);
        assertThat(userPersistence().existsIgnoringDeletion(createdProfile.id())).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(false);
    }

    @Test
    void deliversWhatAnIdentityResolvesToOnSubscribe() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        var identityListener = new RecordingIdentityListener();
        var unknownIdentityListener = new RecordingIdentityListener();

        userPersistence().subscribe(IDENTITY, identityListener);
        userPersistence().subscribe(OTHER_IDENTITY, unknownIdentityListener);

        assertThat(identityListener.firstResolution()).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(new IdentityResolution.Active(createdProfile));
        assertThat(unknownIdentityListener.firstResolution()).succeedsWithin(DELIVERY_TIMEOUT).isEqualTo(IdentityResolution.Absent.INSTANCE);
    }

    /// Every delivery is checked against a fresh [UserPersistence#resolveByIdentity], so the resolution a write derives cannot drift from the one that method
    /// answers with.
    @Test
    void anIdentitysResolutionFollowsEveryChangeToItsUser() {
        var identityListener = new RecordingIdentityListener();
        userPersistence().subscribe(IDENTITY, identityListener);
        identityListener.awaitFirst();

        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        assertLastResolutionMatchesResolveByIdentity(IDENTITY, identityListener);
        await(userPersistence().updateProfile(createdProfile.id(), profileInput("Alexandra")));
        assertLastResolutionMatchesResolveByIdentity(IDENTITY, identityListener);
        await(userPersistence().softDelete(createdProfile.id()));
        assertLastResolutionMatchesResolveByIdentity(IDENTITY, identityListener);
        await(userPersistence().restore(createdProfile.id()));
        assertLastResolutionMatchesResolveByIdentity(IDENTITY, identityListener);
        await(userPersistence().hardDelete(createdProfile.id()));
        assertLastResolutionMatchesResolveByIdentity(IDENTITY, identityListener);

        assertThat(identityListener.resolutions()).hasExactlyElementsOfTypes(IdentityResolution.Absent.class,
                                                                             IdentityResolution.Active.class,
                                                                             IdentityResolution.Active.class,
                                                                             IdentityResolution.SoftDeleted.class,
                                                                             IdentityResolution.Active.class,
                                                                             IdentityResolution.Absent.class);
    }

    @Test
    void anIdentityRepointedToAnotherProviderAccountFollowsItsNewOwner() {
        var replacement = new UserIdentity(OTHER_IDENTITY.provider(), "uid-g2");
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        var replacedIdentityListener = new RecordingIdentityListener();
        var replacementIdentityListener = new RecordingIdentityListener();
        userPersistence().subscribe(OTHER_IDENTITY, replacedIdentityListener);
        userPersistence().subscribe(replacement, replacementIdentityListener);
        replacedIdentityListener.awaitFirst();
        replacementIdentityListener.awaitFirst();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, replacement)));

        assertThat(replacedIdentityListener.resolutions()).containsExactly(new IdentityResolution.Active(createdProfile), IdentityResolution.Absent.INSTANCE);
        assertThat(replacementIdentityListener.resolutions())
                .containsExactly(IdentityResolution.Absent.INSTANCE, new IdentityResolution.Active(createdProfile));
        assertLastResolutionMatchesResolveByIdentity(OTHER_IDENTITY, replacedIdentityListener);
        assertLastResolutionMatchesResolveByIdentity(replacement, replacementIdentityListener);
    }

    /// [UserPersistenceImpl] keeps resolving a dropped identity to its user and the fake forgets it, so the delivery differs between them; what holds for both
    /// is that the subscriber ends up with what [UserPersistence#resolveByIdentity] answers.
    @Test
    void aDroppedIdentityEndsUpResolvingAsResolveByIdentityAnswers() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        var identityListener = new RecordingIdentityListener();
        userPersistence().subscribe(OTHER_IDENTITY, identityListener);
        identityListener.awaitFirst();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY)));

        assertLastResolutionMatchesResolveByIdentity(OTHER_IDENTITY, identityListener);
    }

    @Test
    void staysSilentToAnIdentityWhoseResolutionAWriteLeavesAsItWas() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        var identityListener = new RecordingIdentityListener();
        userPersistence().subscribe(IDENTITY, identityListener);
        identityListener.awaitFirst();

        await(userPersistence().updateAllIdentities(createdProfile.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        await(userPersistence().restore(createdProfile.id()));
        await(userPersistence().updateProfile(createdProfile.id(), profileInput("Alexandra")));

        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(createdProfile),
                                                                   new IdentityResolution.Active(profileNow(createdProfile.id())));
    }

    @Test
    void stopsResolvingAnIdentityForAClosedSubscription() {
        UserProfile createdProfile = createUser(IDENTITY, "Alex");
        var identityListener = new RecordingIdentityListener();
        Closeable subscription = userPersistence().subscribe(IDENTITY, identityListener);
        identityListener.awaitFirst();

        subscription.close();
        await(userPersistence().softDelete(createdProfile.id()));

        assertThat(identityListener.resolutions()).containsExactly(new IdentityResolution.Active(createdProfile));
    }

    private void assertLastResolutionMatchesResolveByIdentity(UserIdentity identity, RecordingIdentityListener identityListener) {
        assertThat(userPersistence().resolveByIdentity(identity))
                .succeedsWithin(DELIVERY_TIMEOUT)
                .satisfies(resolution -> assertThat(identityListener.resolutions()).last().isEqualTo(resolution));
    }

    private Closeable subscribe() {
        return userPersistence().subscribe(stateListener);
    }

    private static void assertChanged(StateEvent event, Consumer<UserProfileWithDeletion> userRequirements) {
        assertThat(event).isInstanceOfSatisfying(Changed.class, changed -> userRequirements.accept(changed.user()));
    }

    private UserProfile createUser(UserIdentity identity, String displayName) {
        return createUser(identity, displayName, "user@example.com");
    }

    private UserProfile createUser(UserIdentity identity, String displayName, String email) {
        return switch (await(userPersistence().getOrCreateByIdentity(identity, new UserProfileInput(email, Optional.of(displayName), UTC)))) {
            case UserPersistence.UserCreationResult.Resolved(var profile, _) -> profile;
            case UserPersistence.UserCreationResult.EmailAlreadyInUse _ -> throw new AssertionError("expected Resolved, got EmailAlreadyInUse");
        };
    }

    /// The user as the store holds them now, whatever their deletion state; fails the test if the store no longer knows them.
    private UserProfile profileNow(String userId) {
        return await(userPersistence().getByIdIgnoringDeletion(userId)).orElseThrow(() -> new AssertionError("user " + userId + " is gone"));
    }

    /// The user with their deletion state as the store holds them now; fails the test if the store no longer knows them.
    private UserProfileWithDeletion userNow(String userId) {
        return await(userPersistence().listAllProfilesIgnoringDeletion()).stream()
                                                                         .filter(user -> user.profile().id().equals(userId))
                                                                         .findFirst()
                                                                         .orElseThrow(() -> new AssertionError("user " + userId + " is gone"));
    }

    /// Blocks on a store call. The bound is a deadlock safety net: a fake completes its futures before returning, and the real store completes them on its own
    /// executor within milliseconds.
    private static <T> T await(CompletableFuture<T> future) {
        return getAsUnchecked(() -> future.get(DELIVERY_TIMEOUT.toSeconds(), SECONDS));
    }

    private static UserProfileInput profileInput(String displayName) {
        return new UserProfileInput("user@example.com", Optional.of(displayName), UTC);
    }

    private interface RepeatableCall {
        CompletableFuture<Void> apply(UserPersistence store, String userId);
    }
}
