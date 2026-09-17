package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.persistence.UserPersistence.UserChange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// The change-stream contract every [UserPersistence] honours, run against each implementation by a subclass. It is shared rather than written twice because a
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

    private List<UserChange> changes;

    /// The started store under test, built afresh for each test by the subclass on [#clock].
    protected abstract UserPersistence userPersistence();

    @BeforeEach
    final void setUpContract() {
        changes = Collections.synchronizedList(new ArrayList<>());
    }

    @Test
    void announcesACreationToASubscriberThatWasAlreadyListening() {
        subscribe();

        UserProfile created = createUser(IDENTITY, "Alex");

        assertThat(changes).singleElement().satisfies(creation -> {
            assertThat(creation.userId()).isEqualTo(created.id());
            assertThat(creation.state()).hasValueSatisfying(state -> assertThat(state.deletedAt()).isEmpty());
        });
    }

    @Test
    void announcesEveryMutationThatChangedSomething() {
        UserProfile created = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateProfile(created.id(), profileInput("Alexandra")));
        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        await(userPersistence().softDelete(created.id()));
        await(userPersistence().restore(created.id()));
        await(userPersistence().hardDelete(created.id()));

        assertThat(changes).extracting(UserChange::userId)
                           .containsExactly(created.id(), created.id(), created.id(), created.id(), created.id());
        assertThat(changes).satisfiesExactly(
                // The payload is read after the commit, so the profile update's own notification already carries the new name.
                profileUpdate -> assertThat(profileUpdate.state()).hasValueSatisfying(state -> {
                    assertThat(state.profile().displayName()).hasValue("Alexandra");
                    assertThat(state.deletedAt()).isEmpty();
                }),
                identityUpdate -> assertThat(identityUpdate.state()).hasValueSatisfying(state -> assertThat(state.deletedAt()).isEmpty()),
                softDelete -> assertThat(softDelete.state()).hasValueSatisfying(state -> assertThat(state.deletedAt()).isPresent()),
                restore -> assertThat(restore.state()).hasValueSatisfying(state -> assertThat(state.deletedAt()).isEmpty()),
                hardDelete -> assertThat(hardDelete.state()).isEmpty());
    }

    /// A restore clears the deletion mark and stamps the update time with the restore instant, so a subscriber sees the row move rather than keep the instant
    /// the soft delete left on it.
    @Test
    void aRestoreStampsTheUpdateTime() {
        UserProfile created = createUser(IDENTITY, "Alex");
        await(userPersistence().softDelete(created.id()));
        Instant softDeletedAt = profileNow(created.id()).updatedAt();
        Instant restoredAt = softDeletedAt.plus(RESTORE_DELAY);
        clock.setTime(restoredAt);
        subscribe();

        await(userPersistence().restore(created.id()));

        assertThat(changes).singleElement()
                           .satisfies(restore -> assertThat(restore.state())
                                   .hasValueSatisfying(state -> assertThat(state.profile().updatedAt()).isEqualTo(restoredAt)));
    }

    @ParameterizedTest
    @MethodSource
    void staysSilentWhenADeletionFindsItsWorkAlreadyDone(Deletion deletion) {
        UserProfile created = createUser(IDENTITY, "Alex");
        await(deletion.apply(userPersistence(), created.id()));
        subscribe();

        await(deletion.apply(userPersistence(), created.id()));

        assertThat(changes).isEmpty();
    }

    static Stream<Arguments> staysSilentWhenADeletionFindsItsWorkAlreadyDone() {
        return Stream.of(arguments(named("soft delete", (Deletion) UserPersistence::softDelete)),
                         arguments(named("hard delete", (Deletion) UserPersistence::hardDelete)));
    }

    @Test
    void staysSilentWhenIdentitiesAreReplacedByTheSameOnes() {
        UserProfile created = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY)));

        assertThat(changes).isEmpty();
    }

    /// The store resolves each provider on its own, so the order a caller lists identities in is not a change. A sign-in provider is free to return them in
    /// any order, and re-announcing on every sign-in would make the stream useless.
    @Test
    void staysSilentWhenTheSameIdentitiesArriveInADifferentOrder() {
        UserProfile created = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        subscribe();

        await(userPersistence().updateAllIdentities(created.id(), List.of(OTHER_IDENTITY, IDENTITY)));

        assertThat(changes).isEmpty();
    }

    @Test
    void announcesAnIdentityThatWasAdded() {
        UserProfile created = createUser(IDENTITY, "Alex");
        subscribe();

        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY, OTHER_IDENTITY)));

        assertThat(changes).singleElement().extracting(UserChange::userId).isEqualTo(created.id());
    }

    @Test
    void announcesAnIdentityThatWasRemoved() {
        UserProfile created = createUser(IDENTITY, "Alex");
        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY, OTHER_IDENTITY)));
        subscribe();

        await(userPersistence().updateAllIdentities(created.id(), List.of(IDENTITY)));

        assertThat(changes).singleElement().extracting(UserChange::userId).isEqualTo(created.id());
    }

    @Test
    void staysSilentWhenGetOrCreateResolvesAnExistingUser() {
        createUser(IDENTITY, "Alex");
        subscribe();

        createUser(IDENTITY, "Alex");

        assertThat(changes).isEmpty();
    }

    /// The mutation has already committed by the time listeners run, so one that throws must reach neither the caller nor the other listeners.
    @Test
    void containsAFailingSubscriberSoTheMutationStillSucceeds() {
        userPersistence().subscribeToChanges(_ -> {
            throw new RuntimeException("listener boom");
        });
        subscribe();

        UserProfile created = createUser(IDENTITY, "Alex");

        assertThat(changes).singleElement().extracting(UserChange::userId).isEqualTo(created.id());
    }

    @Test
    void stopsNotifyingAnUnsubscribedListener() {
        UserProfile created = createUser(IDENTITY, "Alex");
        Closeable subscription = userPersistence().subscribeToChanges(changes::add);

        subscription.close();
        await(userPersistence().softDelete(created.id()));

        assertThat(changes).isEmpty();
    }

    private void subscribe() {
        userPersistence().subscribeToChanges(changes::add);
    }

    private UserProfile createUser(UserIdentity identity, String displayName) {
        return switch (await(userPersistence().getOrCreateByIdentity(identity, profileInput(displayName)))) {
            case UserPersistence.UserCreationResult.Resolved(var profile, _) -> profile;
            case UserPersistence.UserCreationResult.EmailAlreadyInUse _ -> throw new AssertionError("expected Resolved, got EmailAlreadyInUse");
        };
    }

    /// The user as the store holds them now, whatever their deletion state; fails the test if the store no longer knows them.
    private UserProfile profileNow(String userId) {
        return await(userPersistence().getByIdIgnoringDeletion(userId)).orElseThrow(() -> new AssertionError("user " + userId + " is gone"));
    }

    /// Blocks on a store call. The bound is a deadlock safety net: a fake completes its futures before returning, and the real store completes them on its own
    /// executor within milliseconds.
    private static <T> T await(CompletableFuture<T> future) {
        return getAsUnchecked(() -> future.get(5, SECONDS));
    }

    private static UserProfileInput profileInput(String displayName) {
        return new UserProfileInput("user@example.com", Optional.of(displayName), UTC);
    }

    /// One of the store's two deletions, so the "already done, so silent" rule is stated once for both.
    private interface Deletion {
        CompletableFuture<Void> apply(UserPersistence store, String userId);
    }
}
