package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.async.ProgrammableClock;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.time.ZoneOffset.UTC;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.user.persistence.UserPersistenceTestTimeouts.DELIVERY_TIMEOUT;

/// A base for the contract tests of [UserPersistence]. It holds the store under test, the clock on which the store is built, and the identities and methods
/// with which the tests create users in the store.
public abstract class BaseUserPersistenceContractTest {
    protected static final UserIdentity IDENTITY = new UserIdentity("firebase", "uid-1");
    protected static final UserIdentity OTHER_IDENTITY = new UserIdentity("google.com", "uid-g");
    private static final String EMAIL = "user@example.com";
    /// The time to which each subclass sets [#clock] before it builds the store.
    protected static final Instant START = Instant.parse("2026-03-01T10:00:00Z");
    /// The clock on which each subclass builds the store, so that a test can set the time and assert an exact timestamp.
    protected final ProgrammableClock clock = new ProgrammableClock();

    /// The started store under test. The subclass builds a new store on [#clock] for each test.
    protected abstract UserPersistence userPersistence();

    protected final UserProfile createUser(UserIdentity identity, String displayName) {
        return createUser(identity, displayName, EMAIL);
    }

    protected final UserProfile createUser(UserIdentity identity, String displayName, String email) {
        return switch (await(userPersistence().getOrCreateByIdentity(identity, new UserProfileInput(email, Optional.of(displayName), UTC)))) {
            case UserPersistence.UserCreationResult.Resolved(var profile, _) -> profile;
            case UserPersistence.UserCreationResult.EmailAlreadyInUse _ -> throw new AssertionError("expected Resolved, got EmailAlreadyInUse");
        };
    }

    /// @return a profile input equal to the one that [#createUser(UserIdentity, String)] uses to create a user with `displayName`
    protected static UserProfileInput createProfileInput(String displayName) {
        return new UserProfileInput(EMAIL, Optional.of(displayName), UTC);
    }

    /// Waits up to the delivery timeout for the future of a call to the store, and returns the future's result. Only a deadlock reaches the timeout:
    /// [FakeUserPersistence] completes its futures before its methods return, and [UserPersistenceImpl] completes them on its own executor within milliseconds.
    ///
    /// @param <T> the type of the future's result
    protected static <T> T await(CompletableFuture<T> future) {
        return getAsUnchecked(() -> future.get(DELIVERY_TIMEOUT.toSeconds(), SECONDS));
    }
}
