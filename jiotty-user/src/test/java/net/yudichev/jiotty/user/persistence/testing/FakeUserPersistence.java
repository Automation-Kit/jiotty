package net.yudichev.jiotty.user.persistence.testing;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.persistence.UserIdentity;
import net.yudichev.jiotty.user.persistence.UserIdentityRecord;
import net.yudichev.jiotty.user.persistence.UserPersistence;
import net.yudichev.jiotty.user.persistence.UserProfile;
import net.yudichev.jiotty.user.persistence.UserProfileInput;
import net.yudichev.jiotty.user.persistence.UserProfileWithDeletion;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.CompletableFuture.completedFuture;

public final class FakeUserPersistence implements UserPersistence {
    private final Object lock = new Object();
    private final CurrentDateTimeProvider timeProvider;
    private final Map<String, StoredUser> usersById = new LinkedHashMap<>();
    private final Map<UserIdentity, String> activeUserIdsByIdentity = new HashMap<>();
    private final Listeners<String> changeListeners = new Listeners<>();

    private int nextUserNumber = 1;
    private @Nullable CompletableFuture<Void> nextResolveByIdentityGate;

    public FakeUserPersistence(CurrentDateTimeProvider timeProvider) {
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    }

    @Override
    public CompletableFuture<UserCreationResult> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        synchronized (lock) {
            checkNotNull(identity, "identity");
            checkNotNull(profile, "profile");
            // Mirrors UserPersistenceImpl's unique-constraint handling, including which state maps to which outcome: both of the real store's constraints span
            // soft-deleted rows, so a soft-deleted user holding this identity blocks creation and is not an email conflict.
            switch (resolveByIdentityLocked(identity)) {
                case IdentityResolution.Active(UserProfile existingProfile) -> {
                    return completedFuture(new UserCreationResult.Resolved(existingProfile));
                }
                case IdentityResolution.SoftDeleted _ ->
                        throw new IllegalStateException("Identity " + identity + " belongs to a soft-deleted user; resolve it before creating");
                case IdentityResolution.Absent _ -> {
                }
            }
            // A taken address is an outcome the caller branches on, not a failure — the real store's unique-violation path returns the same value.
            if (isEmailTaken(profile.email().orElse(null), null)) {
                return completedFuture(UserCreationResult.EmailAlreadyInUse.INSTANCE);
            }
            Instant timestamp = currentInstant();
            String userId = "u" + nextUserNumber++;
            var createdProfile = new UserProfile(userId, profile.email(), profile.displayName(), profile.timezone(), timestamp, timestamp);
            var storedUser = new StoredUser(createdProfile);
            storedUser.replaceActiveIdentities(List.of(identity), timestamp);
            usersById.put(userId, storedUser);
            activeUserIdsByIdentity.put(identity, userId);
            changeListeners.notify(userId);
            return completedFuture(new UserCreationResult.Resolved(createdProfile));
        }
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getByIdentity(UserIdentity identity) {
        synchronized (lock) {
            return completedFuture(findActiveProfileByIdentityLocked(identity));
        }
    }

    @Override
    public CompletableFuture<IdentityResolution> resolveByIdentity(UserIdentity identity) {
        synchronized (lock) {
            checkNotNull(identity, "identity");
            IdentityResolution resolution = resolveByIdentityLocked(identity);
            CompletableFuture<Void> gate = nextResolveByIdentityGate;
            nextResolveByIdentityGate = null;
            return gate == null ? completedFuture(resolution) : gate.thenApply(_ -> resolution);
        }
    }

    /// Holds the next [#resolveByIdentity] call's result until the returned gate is completed, so a test can keep an identity resolution in flight while it
    /// delivers other events. One-shot: only the next call is held, and its resolution is still computed from the store state at the time of that call.
    public CompletableFuture<Void> deferNextResolveByIdentity() {
        synchronized (lock) {
            var gate = new CompletableFuture<Void>();
            nextResolveByIdentityGate = gate;
            return gate;
        }
    }

    private IdentityResolution resolveByIdentityLocked(UserIdentity identity) {
        String activeUserId = activeUserIdsByIdentity.get(identity);
        if (activeUserId != null) {
            return new IdentityResolution.Active(usersById.get(activeUserId).profile());
        }
        for (StoredUser user : usersById.values()) {
            if (!user.active() && user.activeIdentities().contains(identity)) {
                return new IdentityResolution.SoftDeleted(user.profile());
            }
        }
        return IdentityResolution.Absent.INSTANCE;
    }

    @Override
    public CompletableFuture<Optional<UserProfile>> getById(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            return completedFuture(storedUser == null || !storedUser.active() ? Optional.empty() : Optional.of(storedUser.profile()));
        }
    }

    @Override
    public CompletableFuture<List<UserProfile>> listAllProfiles() {
        synchronized (lock) {
            var profiles = ImmutableList.<UserProfile>builder();
            usersById.values().forEach(user -> {
                if (user.active()) {
                    profiles.add(user.profile());
                }
            });
            return completedFuture(profiles.build());
        }
    }

    @Override
    public CompletableFuture<List<UserProfileWithDeletion>> listAllProfilesIgnoringDeletion() {
        synchronized (lock) {
            var profiles = ImmutableList.<UserProfileWithDeletion>builder();
            usersById.values().forEach(user -> profiles.add(new UserProfileWithDeletion(user.profile(), user.deletedAt())));
            return completedFuture(profiles.build());
        }
    }

    @Override
    public CompletableFuture<Boolean> existsIgnoringDeletion(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            return completedFuture(usersById.containsKey(userId));
        }
    }

    @Override
    public CompletableFuture<UserProfile> updateProfile(String userId, UserProfileInput profile) {
        synchronized (lock) {
            checkNotNull(profile, "profile");
            StoredUser storedUser = getActiveUser(userId);
            ensureEmailAvailable(profile.email().orElse(null), userId);
            Instant timestamp = currentInstant();
            var updatedProfile = new UserProfile(userId,
                                                 profile.email(),
                                                 profile.displayName(),
                                                 profile.timezone(),
                                                 storedUser.profile().createdAt(),
                                                 timestamp);
            storedUser.updateProfile(updatedProfile);
            changeListeners.notify(userId);
            return completedFuture(updatedProfile);
        }
    }

    @Override
    public CompletableFuture<Void> updateAllIdentities(String userId, List<UserIdentity> identities) {
        synchronized (lock) {
            checkNotNull(identities, "identities");
            StoredUser storedUser = getActiveUser(userId);
            var identitiesByProvider = LinkedHashMap.<String, UserIdentity>newLinkedHashMap(identities.size());
            for (UserIdentity identity : identities) {
                checkNotNull(identity, "identity");
                UserIdentity previousIdentity = identitiesByProvider.put(identity.provider(), identity);
                checkArgument(previousIdentity == null, "Duplicate provider in identities: %s", identity.provider());
                String ownerUserId = activeUserIdsByIdentity.get(identity);
                checkState(ownerUserId == null || ownerUserId.equals(userId), "Identity %s is already linked to another user", identity);
            }
            for (UserIdentity oldIdentity : storedUser.activeIdentities()) {
                String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
                assert userId.equals(removedUserId);
            }
            Instant timestamp = currentInstant();
            storedUser.replaceActiveIdentities(identitiesByProvider.values(), timestamp);
            identitiesByProvider.values().forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
            changeListeners.notify(userId);
            return completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<List<UserIdentityRecord>> listIdentities(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            return completedFuture(storedUser == null || !storedUser.active() ? ImmutableList.of() : storedUser.activeIdentityRecords());
        }
    }

    @Override
    public CompletableFuture<List<UserIdentityRecord>> listIdentitiesIgnoringDeletion(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            return completedFuture(storedUser == null ? ImmutableList.of() : storedUser.activeIdentityRecords());
        }
    }

    @Override
    public CompletableFuture<Void> softDelete(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser == null || !storedUser.active()) {
                return completedFuture(null); // idempotent: already soft-deleted (or unknown) — no-op
            }
            for (UserIdentity oldIdentity : storedUser.activeIdentities()) {
                String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
                assert userId.equals(removedUserId);
            }
            storedUser.softDelete(currentInstant());
            changeListeners.notify(userId);
            return completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Void> hardDelete(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser removed = usersById.remove(userId);
            if (removed != null) {
                activeUserIdsByIdentity.values().removeIf(userId::equals);
                changeListeners.notify(userId);
            }
            return completedFuture(null);
        }
    }

    @Override
    public CompletableFuture<Void> restore(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser != null && !storedUser.active()) {
                storedUser.restore();
                storedUser.activeIdentities().forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
                changeListeners.notify(userId);
            }
            return completedFuture(null);
        }
    }

    /// Notifies on the calling thread inside this fake's reentrant lock, matching the real store, which notifies on the thread that performed the write.
    @Override
    public Closeable subscribeToChanges(Consumer<? super String> userIdUpdateListener) {
        return changeListeners.addListener(checkNotNull(userIdUpdateListener, "listener"));
    }

    public Optional<UserProfile> findActiveProfileByIdentity(UserIdentity identity) {
        synchronized (lock) {
            return findActiveProfileByIdentityLocked(identity);
        }
    }

    public List<UserIdentityRecord> listActiveIdentityRecords(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            return storedUser == null || !storedUser.active() ? ImmutableList.of() : storedUser.activeIdentityRecords();
        }
    }

    /// Whether `email` already belongs to a user other than `ignoredUserId`. Soft-deleted users count: the real store holds one email per user until the row is
    /// hard-deleted, so exempting them here would let the fake create a second user for an address the real store rejects — and hide exactly that regression.
    ///
    /// @param email         null when the profile carries no address, which can never conflict
    /// @param ignoredUserId the user permitted to already hold the address; null exempts nobody
    private boolean isEmailTaken(@Nullable String email, @Nullable String ignoredUserId) {
        return email != null
               && usersById.values().stream()
                           .anyMatch(user -> user.profile().email().filter(email::equals).isPresent()
                                             && !user.profile().id().equals(ignoredUserId));
    }

    /// Rejects a profile whose email already belongs to another user, mirroring the real store's unique-email constraint.
    ///
    /// @param email         null when the profile carries no address, which can never conflict
    /// @param ignoredUserId the user permitted to already hold the address; null exempts nobody
    private void ensureEmailAvailable(@Nullable String email, @Nullable String ignoredUserId) {
        if (isEmailTaken(email, ignoredUserId)) {
            throw new IllegalStateException("Email is already linked to another user: " + email);
        }
    }

    private StoredUser getActiveUser(String userId) {
        checkNotNull(userId, "userId");
        StoredUser storedUser = usersById.get(userId);
        checkState(storedUser != null, "Unknown user ID %s", userId);
        checkState(storedUser.active(), "User %s is deleted", userId);
        return storedUser;
    }

    private Optional<UserProfile> findActiveProfileByIdentityLocked(UserIdentity identity) {
        checkNotNull(identity, "identity");
        String userId = activeUserIdsByIdentity.get(identity);
        return userId == null ? Optional.empty() : Optional.of(usersById.get(userId).profile());
    }

    private Instant currentInstant() {
        return timeProvider.currentInstant();
    }

    private static final class StoredUser {
        private final Map<String, UserIdentityRecord> activeIdentityRecordsByProvider = new LinkedHashMap<>();

        private UserProfile profile;
        private @Nullable Instant deletedAt;

        private StoredUser(UserProfile profile) {
            this.profile = checkNotNull(profile, "profile");
        }

        public boolean active() {
            return deletedAt == null;
        }

        public Optional<Instant> deletedAt() {
            return Optional.ofNullable(deletedAt);
        }

        public UserProfile profile() {
            return profile;
        }

        public List<UserIdentity> activeIdentities() {
            var identities = ImmutableList.<UserIdentity>builder();
            activeIdentityRecordsByProvider.values().forEach(record -> identities.add(record.identity()));
            return identities.build();
        }

        public List<UserIdentityRecord> activeIdentityRecords() {
            return ImmutableList.copyOf(activeIdentityRecordsByProvider.values());
        }

        public void updateProfile(UserProfile profile) {
            this.profile = checkNotNull(profile, "profile");
        }

        public void replaceActiveIdentities(Iterable<UserIdentity> identities, Instant updatedAt) {
            var newIdentityRecordsByProvider = new LinkedHashMap<String, UserIdentityRecord>();
            for (UserIdentity identity : identities) {
                UserIdentityRecord previousRecord = activeIdentityRecordsByProvider.get(identity.provider());
                Instant createdAt = previousRecord == null ? updatedAt : previousRecord.createdAt();
                UserIdentityRecord replacedRecord = newIdentityRecordsByProvider.put(identity.provider(),
                                                                                     new UserIdentityRecord(identity, createdAt, updatedAt));
                assert replacedRecord == null;
            }
            activeIdentityRecordsByProvider.clear();
            activeIdentityRecordsByProvider.putAll(newIdentityRecordsByProvider);
        }

        public void softDelete(Instant deletedAt) {
            // Keep the identity records (marked inactive via the deletion timestamp) so restore() can revive them, mirroring UserPersistenceImpl.
            this.deletedAt = deletedAt;
            profile = new UserProfile(profile.id(), profile.email(), profile.displayName(), profile.timezone(), profile.createdAt(), deletedAt);
        }

        public void restore() {
            deletedAt = null;
        }
    }
}
