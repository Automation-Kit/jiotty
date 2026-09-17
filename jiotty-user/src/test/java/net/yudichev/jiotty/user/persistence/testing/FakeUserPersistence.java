package net.yudichev.jiotty.user.persistence.testing;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.persistence.ConditionalSoftDeleteOutcome;
import net.yudichev.jiotty.user.persistence.UserIdentity;
import net.yudichev.jiotty.user.persistence.UserIdentityRecord;
import net.yudichev.jiotty.user.persistence.UserPersistence;
import net.yudichev.jiotty.user.persistence.UserProfile;
import net.yudichev.jiotty.user.persistence.UserProfileInput;
import net.yudichev.jiotty.user.persistence.UserProfileWithDeletion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Comparator;
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
    private static final Logger logger = LogManager.getLogger(FakeUserPersistence.class);

    private final Object lock = new Object();
    private final CurrentDateTimeProvider timeProvider;
    private final Map<String, StoredUser> usersById = new LinkedHashMap<>();
    private final Map<UserIdentity, String> activeUserIdsByIdentity = new HashMap<>();
    private final Listeners<UserChange> changeListeners = new Listeners<>();

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
                    return completedFuture(new UserCreationResult.Resolved(existingProfile, false));
                }
                case IdentityResolution.SoftDeleted _ ->
                        throw new IllegalStateException("Identity " + identity + " belongs to a soft-deleted user; resolve it before creating");
                case IdentityResolution.Absent _ -> {
                }
            }
            // A taken address is an outcome the caller branches on, not a failure — the real store's unique-violation path returns the same value.
            if (isEmailTaken(profile.email(), null)) {
                return completedFuture(UserCreationResult.EmailAlreadyInUse.INSTANCE);
            }
            Instant timestamp = currentInstant();
            String userId = "u" + nextUserNumber++;
            var createdProfile = new UserProfile(userId, profile.email(), profile.displayName(), profile.timezone(), timestamp, timestamp);
            var storedUser = new StoredUser(createdProfile);
            storedUser.replaceActiveIdentities(List.of(identity), timestamp);
            usersById.put(userId, storedUser);
            activeUserIdsByIdentity.put(identity, userId);
            notifyChanged(userId, storedUser);
            return completedFuture(new UserCreationResult.Resolved(createdProfile, true));
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
    public CompletableFuture<Optional<UserProfile>> getByIdIgnoringDeletion(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            return completedFuture(Optional.ofNullable(usersById.get(userId)).map(StoredUser::profile));
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
    public CompletableFuture<Boolean> touchLastActive(String userId, Instant activeAt) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            checkNotNull(activeAt, "activeAt");
            StoredUser storedUser = usersById.get(userId);
            // Soft-deleted users are touched too, mirroring UserPersistenceImpl, so a restored row carries what was recorded while it was deleted.
            if (storedUser == null || !storedUser.lastActiveAt().isBefore(activeAt)) {
                return completedFuture(false);
            }
            storedUser.touchLastActive(activeAt);
            return completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<List<UserProfile>> listInactiveSince(Instant cutoff, int limit) {
        synchronized (lock) {
            checkNotNull(cutoff, "cutoff");
            checkArgument(limit > 0, "limit must be positive, got %s", limit);
            var profiles = ImmutableList.<UserProfile>builder();
            // Least recently active first and capped, as the interface declares — a fake that returned insertion order, or everything, would pass tests the
            // real store fails.
            usersById.values().stream()
                     .filter(user -> user.active() && user.lastActiveAt().isBefore(cutoff))
                     .sorted(Comparator.comparing(StoredUser::lastActiveAt))
                     .limit(limit)
                     .forEach(user -> profiles.add(user.profile()));
            return completedFuture(profiles.build());
        }
    }

    @Override
    public CompletableFuture<ConditionalSoftDeleteOutcome> softDeleteIfInactiveSince(String userId, Instant cutoff) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            checkNotNull(cutoff, "cutoff");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser == null) {
                return completedFuture(ConditionalSoftDeleteOutcome.ABSENT);
            }
            if (!storedUser.active()) {
                return completedFuture(ConditionalSoftDeleteOutcome.ALREADY_SOFT_DELETED);
            }
            if (!storedUser.lastActiveAt().isBefore(cutoff)) {
                return completedFuture(ConditionalSoftDeleteOutcome.STILL_ACTIVE);
            }
            softDeleteLocked(userId, storedUser);
            return completedFuture(ConditionalSoftDeleteOutcome.SOFT_DELETED);
        }
    }

    @Override
    public CompletableFuture<UserProfile> updateProfile(String userId, UserProfileInput profile) {
        synchronized (lock) {
            checkNotNull(profile, "profile");
            StoredUser storedUser = getActiveUser(userId);
            ensureEmailAvailable(profile.email(), userId);
            Instant timestamp = currentInstant();
            var updatedProfile = new UserProfile(userId,
                                                 profile.email(),
                                                 profile.displayName(),
                                                 profile.timezone(),
                                                 storedUser.profile().createdAt(),
                                                 timestamp);
            storedUser.updateProfile(updatedProfile);
            notifyChanged(userId, storedUser);
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
            // The real store writes an identity row where one is new, changed or dropped, and announces nothing when none of those applies. It looks each
            // provider up on its own, so the order the caller supplies them in makes no difference — hence the set comparison.
            List<UserIdentity> previousIdentities = storedUser.activeIdentities();
            boolean anyIdentityWritten = !ImmutableSet.copyOf(previousIdentities).equals(ImmutableSet.copyOf(identitiesByProvider.values()));
            for (UserIdentity oldIdentity : previousIdentities) {
                String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
                assert userId.equals(removedUserId);
            }
            Instant timestamp = currentInstant();
            storedUser.replaceActiveIdentities(identitiesByProvider.values(), timestamp);
            identitiesByProvider.values().forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
            if (anyIdentityWritten) {
                notifyChanged(userId, storedUser);
            }
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
            softDeleteLocked(userId, storedUser);
            return completedFuture(null);
        }
    }

    private void softDeleteLocked(String userId, StoredUser storedUser) {
        for (UserIdentity oldIdentity : storedUser.activeIdentities()) {
            String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
            assert userId.equals(removedUserId);
        }
        storedUser.softDelete(currentInstant());
        notifyChanged(userId, storedUser);
    }

    @Override
    public CompletableFuture<Void> hardDelete(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser removed = usersById.remove(userId);
            if (removed != null) {
                activeUserIdsByIdentity.values().removeIf(userId::equals);
                notifyChanged(userId, null);
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
                storedUser.restore(currentInstant());
                storedUser.activeIdentities().forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
                notifyChanged(userId, storedUser);
            }
            return completedFuture(null);
        }
    }

    /// Notifies on the calling thread inside this fake's reentrant lock, matching the real store, which notifies on the thread that performed the write.
    @Override
    public Closeable subscribeToChanges(Consumer<? super UserChange> listener) {
        return changeListeners.addListener(checkNotNull(listener, "listener"));
    }

    /// Announces a change to the subscribers of [#subscribeToChanges], carrying the user's state as this fake holds it once the change has been applied. A
    /// listener's failure is contained here as the real store contains it, so one throwing subscriber cannot fail the mutation that is already applied.
    ///
    /// @param storedUser the user as it now stands, or `null` once erased
    private void notifyChanged(String userId, @Nullable StoredUser storedUser) {
        try {
            changeListeners.notify(new UserChange(userId,
                                                  Optional.ofNullable(storedUser)
                                                          .map(user -> new UserProfileWithDeletion(user.profile(), user.deletedAt()))));
        } catch (RuntimeException e) {
            logger.info("[{}] User-change listener failed", userId, e);
        }
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
    /// @param ignoredUserId the user permitted to already hold the address; null exempts nobody
    private boolean isEmailTaken(String email, @Nullable String ignoredUserId) {
        return usersById.values().stream()
                        .anyMatch(user -> user.profile().email().equals(email) && !user.profile().id().equals(ignoredUserId));
    }

    /// Rejects a profile whose email already belongs to another user, mirroring the real store's unique-email constraint.
    ///
    /// @param ignoredUserId the user permitted to already hold the address; null exempts nobody
    private void ensureEmailAvailable(String email, @Nullable String ignoredUserId) {
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
        private Instant lastActiveAt;
        private @Nullable Instant deletedAt;

        private StoredUser(UserProfile profile) {
            this.profile = checkNotNull(profile, "profile");
            // Creating the account is the first activity, matching the value UserPersistenceImpl's insert writes.
            lastActiveAt = profile.createdAt();
        }

        public Instant lastActiveAt() {
            return lastActiveAt;
        }

        public void touchLastActive(Instant activeAt) {
            lastActiveAt = activeAt;
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

        /// Mirrors `restoreUserSql`, which stamps `updated_at` with the restore instant as it clears `deleted_at`.
        public void restore(Instant restoredAt) {
            deletedAt = null;
            profile = new UserProfile(profile.id(), profile.email(), profile.displayName(), profile.timezone(), profile.createdAt(), restoredAt);
        }
    }
}
