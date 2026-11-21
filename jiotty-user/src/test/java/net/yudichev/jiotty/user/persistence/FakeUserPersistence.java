package net.yudichev.jiotty.user.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.observer.SerialisedListeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.persistence.UserStatePublisher.IdentitySubscription;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.CompletableFuture.completedFuture;

public final class FakeUserPersistence implements UserPersistence {
    private final Object lock = new Object();
    private final CurrentDateTimeProvider timeProvider;
    private final Map<String, StoredUser> usersById = new LinkedHashMap<>();
    private final Map<UserIdentity, String> activeUserIdsByIdentity = new HashMap<>();
    private final UserStatePublisher publisher = new UserStatePublisher(command -> {
        synchronized (lock) {
            command.run();
        }
    });

    private final List<UserStatePublisher.IdentitySubscription> identitySubscriptions = new ArrayList<>();

    private int nextUserNumber = 1;
    private @Nullable RuntimeException identitySubscriptionFailure;
    private @Nullable CompletableFuture<Void> nextIdentityUpdateAnswer;

    public FakeUserPersistence(CurrentDateTimeProvider timeProvider) {
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    }

    @Override
    public CompletableFuture<UserCreationResult> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile) {
        synchronized (lock) {
            checkNotNull(identity, "identity");
            checkNotNull(profile, "profile");
            // This switch returns the same outcome for each `IdentityResolution` as the unique-constraint handling of `UserPersistenceImpl`. Both unique
            // constraints of `UserPersistenceImpl` cover soft-deleted rows, so creation fails when a soft-deleted user holds this identity, and the failure is
            // not an email conflict.
            switch (resolveByIdentityLocked(identity)) {
                case IdentityResolution.Active(UserProfile existingProfile) -> {
                    return completedFuture(new UserCreationResult.Resolved(existingProfile, false));
                }
                case IdentityResolution.SoftDeleted _ ->
                        throw new IllegalStateException("Identity " + identity + " belongs to a soft-deleted user; resolve it before creating");
                case IdentityResolution.Absent _ -> {
                }
            }
            // An email address that a user already holds is an outcome on which the caller branches. This fake returns `EmailAlreadyInUse` for it, as
            // `UserPersistenceImpl` does when its unique constraint rejects the email.
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
            announceChanged(storedUser, List.of(identity), List.of());
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
            return completedFuture(resolveByIdentityLocked(identity));
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
            return completedFuture(listAllProfilesIgnoringDeletionLocked());
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
            // As in `UserPersistenceImpl`, this method updates a soft-deleted user, so a restored user has the last-active time recorded while the user was
            // soft-deleted.
            if (storedUser == null || !storedUser.lastActiveAt().isBefore(activeAt)) {
                return completedFuture(false);
            }
            storedUser.touchLastActive(activeAt);
            return completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<ActivityRecordOutcome> recordActivity(String userId, String kind, LocalDate day) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            checkNotNull(kind, "kind");
            checkNotNull(day, "day");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser == null) {
                return completedFuture(ActivityRecordOutcome.ABSENT);
            }
            Instant startOfDay = day.atStartOfDay(ZoneOffset.UTC).toInstant();
            if (storedUser.lastActiveAt().isBefore(startOfDay)) {
                storedUser.touchLastActive(startOfDay);
            }
            if (storedUser.activityHistoryDisabled()) {
                return completedFuture(ActivityRecordOutcome.HISTORY_OFF);
            }
            if (storedUser.addActivityDay(kind, day)) {
                announceRecordChanged(storedUser);
            }
            return completedFuture(ActivityRecordOutcome.RECORDED);
        }
    }

    @Override
    public CompletableFuture<Boolean> setActivityHistoryDisabled(String userId, boolean disabled) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser == null) {
                return completedFuture(false);
            }
            if (storedUser.setActivityHistoryDisabled(disabled)) {
                announceRecordChanged(storedUser);
            }
            return completedFuture(true);
        }
    }

    /// Stores the attachment as parsed JSON, as the `jsonb` column of [UserPersistenceImpl] does. When `json` equals the stored value apart from whitespace
    /// and key order, the fake delivers no record change. [#readRecord] returns the stored value serialised again.
    @Override
    public CompletableFuture<Boolean> putAttachment(String userId, String name, String json) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            checkNotNull(name, "name");
            UserPersistence.checkAttachmentLength(name, json);
            StoredUser storedUser = usersById.get(userId);
            if (storedUser == null) {
                return completedFuture(false);
            }
            if (storedUser.putAttachment(name, Json.parse(json))) {
                announceRecordChanged(storedUser);
            }
            return completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<Integer> purgeActivityDaysBefore(LocalDate day) {
        synchronized (lock) {
            checkNotNull(day, "day");
            int purgedDayCount = 0;
            for (StoredUser storedUser : usersById.values()) {
                purgedDayCount += storedUser.purgeActivityDaysBefore(day);
            }
            if (purgedDayCount > 0) {
                publisher.publishDaysPurged(day);
            }
            return completedFuture(purgedDayCount);
        }
    }

    @Override
    public CompletableFuture<Optional<UserRecord>> readRecord(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            return completedFuture(Optional.ofNullable(usersById.get(userId)).map(StoredUser::createRecord));
        }
    }

    @Override
    public CompletableFuture<List<UserProfile>> listInactiveSince(Instant cutoff, int limit) {
        synchronized (lock) {
            checkNotNull(cutoff, "cutoff");
            checkArgument(limit > 0, "limit must be positive, got %s", limit);
            var profiles = ImmutableList.<UserProfile>builder();
            // The interface declares the profiles ordered least recently active first, and at most `limit` of them. A fake that returned the profiles in
            // insertion order, or all of them, would pass tests that fail against `UserPersistenceImpl`.
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
            announceChanged(storedUser, List.of(), List.of());
            return completedFuture(updatedProfile);
        }
    }

    @Override
    public CompletableFuture<Void> updateAllIdentities(String userId, List<UserIdentity> identities) {
        synchronized (lock) {
            checkNotNull(identities, "identities");
            if (nextIdentityUpdateAnswer != null) {
                CompletableFuture<Void> answer = nextIdentityUpdateAnswer;
                nextIdentityUpdateAnswer = null;
                return answer;
            }
            StoredUser storedUser = getActiveUser(userId);
            var identitiesByProvider = LinkedHashMap.<String, UserIdentity>newLinkedHashMap(identities.size());
            for (UserIdentity identity : identities) {
                checkNotNull(identity, "identity");
                UserIdentity previousIdentity = identitiesByProvider.put(identity.provider(), identity);
                checkArgument(previousIdentity == null, "Duplicate provider in identities: %s", identity.provider());
                String ownerUserId = activeUserIdsByIdentity.get(identity);
                checkState(ownerUserId == null || ownerUserId.equals(userId), "Identity %s is already linked to another user", identity);
            }
            // `UserPersistenceImpl` writes the row of each identity that is new, changed or dropped, and publishes a change only when it writes a row. It looks
            // up each provider separately, so the order of `identities` has no effect, and this fake therefore compares the identities as sets.
            Set<UserIdentity> previousIdentities = ImmutableSet.copyOf(storedUser.activeIdentities());
            Set<UserIdentity> newIdentities = ImmutableSet.copyOf(identitiesByProvider.values());
            for (UserIdentity oldIdentity : previousIdentities) {
                String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
                assert userId.equals(removedUserId);
            }
            Instant timestamp = currentInstant();
            storedUser.replaceActiveIdentities(newIdentities, timestamp);
            newIdentities.forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
            if (!previousIdentities.equals(newIdentities)) {
                // This fake deletes a dropped identity, so a dropped identity resolves to no user and is announced as unlinked. In `UserPersistenceImpl`, a
                // dropped identity resolves to this user.
                announceChanged(storedUser, Sets.difference(newIdentities, previousIdentities), Sets.difference(previousIdentities, newIdentities));
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
            if (storedUser != null) {
                if (storedUser.active()) {
                    softDeleteLocked(userId, storedUser);
                } else {
                    announceChanged(storedUser, List.of(), List.of());
                }
            }
            return completedFuture(null);
        }
    }

    private void softDeleteLocked(String userId, StoredUser storedUser) {
        for (UserIdentity oldIdentity : storedUser.activeIdentities()) {
            String removedUserId = activeUserIdsByIdentity.remove(oldIdentity);
            assert userId.equals(removedUserId);
        }
        storedUser.softDelete(currentInstant());
        announceChanged(storedUser, List.of(), List.of());
    }

    @Override
    public CompletableFuture<Void> hardDelete(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            if (usersById.remove(userId) != null) {
                activeUserIdsByIdentity.values().removeIf(userId::equals);
            }
            return publisher.publishRemoved(userId);
        }
    }

    @Override
    public CompletableFuture<Void> restore(String userId) {
        synchronized (lock) {
            checkNotNull(userId, "userId");
            StoredUser storedUser = usersById.get(userId);
            if (storedUser != null) {
                if (!storedUser.active()) {
                    storedUser.restore(currentInstant());
                    storedUser.activeIdentities().forEach(identity -> activeUserIdsByIdentity.put(identity, userId));
                }
                announceChanged(storedUser, List.of(), List.of());
            }
            return completedFuture(null);
        }
    }

    /// Delivers the image at once, on the calling thread inside this fake's lock, where the real implementation delivers it from a task of its own; each later
    /// change is delivered on the thread that made it, as the real implementation's are.
    @Override
    public Closeable subscribe(UserStateListener listener) {
        synchronized (lock) {
            SerialisedListeners<UserStateListener>.Subscription subscription = publisher.newSubscription(listener);
            publisher.activate(subscription, listAllProfilesIgnoringDeletionLocked());
            return subscription;
        }
    }

    private List<UserProfileWithDeletion> listAllProfilesIgnoringDeletionLocked() {
        var profiles = ImmutableList.<UserProfileWithDeletion>builderWithExpectedSize(usersById.size());
        usersById.values().forEach(user -> profiles.add(user.createProfileWithDeletion()));
        return profiles.build();
    }

    /// Delivers the first resolution as [#subscribe(UserStateListener)] delivers the image.
    @Override
    public Closeable subscribe(UserIdentity identity, IdentityResolutionListener listener) {
        synchronized (lock) {
            IdentitySubscription subscription = publisher.newSubscription(identity, listener);
            identitySubscriptions.add(subscription);
            if (identitySubscriptionFailure == null) {
                publisher.activate(subscription, resolveByIdentityLocked(identity));
            } else {
                listener.onSubscriptionFailed(identitySubscriptionFailure);
            }
            return subscription;
        }
    }

    /// Delivers the image of the records in the same way as [#subscribe(UserStateListener)] delivers the image of the users.
    @Override
    public Closeable subscribe(LocalDate earliestDay, UserRecordListener listener) {
        synchronized (lock) {
            SerialisedListeners<UserRecordListener>.Subscription subscription = publisher.newSubscription(earliestDay, listener);
            var records = ImmutableList.<UserRecord>builderWithExpectedSize(usersById.size());
            usersById.values().forEach(user -> records.add(user.createRecord().withoutDaysBefore(earliestDay)));
            publisher.activateRecords(subscription, records.build());
            return subscription;
        }
    }

    /// Makes every later identity subscription fail with `failure`, as the real implementation's does when it cannot read the first resolution.
    public void failIdentitySubscriptionsWith(RuntimeException failure) {
        synchronized (lock) {
            identitySubscriptionFailure = checkNotNull(failure, "failure");
        }
    }

    /// Lets later identity subscriptions succeed again.
    public void stopFailingIdentitySubscriptions() {
        synchronized (lock) {
            identitySubscriptionFailure = null;
        }
    }

    /// Answers the next [#updateAllIdentities] with `answer` and applies nothing, so a test can fail that write or hold it in flight.
    public void answerNextIdentityUpdateWith(CompletableFuture<Void> answer) {
        synchronized (lock) {
            nextIdentityUpdateAnswer = checkNotNull(answer, "answer");
        }
    }

    /// How many identity subscriptions this fake has handed out that their holders have not closed, failed ones included.
    public int openIdentitySubscriptionCount() {
        synchronized (lock) {
            return (int) identitySubscriptions.stream().filter(subscription -> !subscription.isClosed()).count();
        }
    }

    /// Announces a write that left `storedUser` in this fake, as it stands once the write has been applied.
    ///
    /// @param linkedIdentities   identities the write made resolve to the user
    /// @param unlinkedIdentities identities the write made resolve to nobody
    private void announceChanged(StoredUser storedUser, Collection<UserIdentity> linkedIdentities, Collection<UserIdentity> unlinkedIdentities) {
        publisher.publishChanged(storedUser.createProfileWithDeletion(), linkedIdentities, unlinkedIdentities);
    }

    /// Delivers the record of `storedUser` to the record subscribers, as the record stands after a write that changed it.
    private void announceRecordChanged(StoredUser storedUser) {
        publisher.publishRecordChanged(storedUser.createRecord());
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

    /// Whether `email` already belongs to a user other than `ignoredUserId`, soft-deleted users included. [UserPersistenceImpl] keeps an email unique across
    /// users until the user's row is hard-deleted, so counting soft-deleted users makes the fake reject a second user for each address that
    /// [UserPersistenceImpl] rejects.
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

        private final SetMultimap<String, LocalDate> activityDaysByKind = HashMultimap.create();
        private final Map<String, JsonNode> attachmentsByName = new HashMap<>();

        private UserProfile profile;
        private Instant lastActiveAt;
        private @Nullable Instant deletedAt;
        private boolean activityHistoryDisabled;

        private StoredUser(UserProfile profile) {
            this.profile = checkNotNull(profile, "profile");
            // Creating the account counts as the user's first activity, truncated to the day, as in `UserPersistenceImpl`.
            lastActiveAt = profile.createdAt().truncatedTo(ChronoUnit.DAYS);
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

        public UserProfileWithDeletion createProfileWithDeletion() {
            return new UserProfileWithDeletion(profile, deletedAt());
        }

        public boolean activityHistoryDisabled() {
            return activityHistoryDisabled;
        }

        /// @return `true` if this call added `day` under `kind`; `false` if the user already held `day` under `kind`
        public boolean addActivityDay(String kind, LocalDate day) {
            return activityDaysByKind.put(kind, day);
        }

        /// Sets [#activityHistoryDisabled()] to `disabled`. Disabling the history deletes the recorded days, as [UserPersistenceImpl] does.
        ///
        /// @return whether [#activityHistoryDisabled()] changed
        public boolean setActivityHistoryDisabled(boolean disabled) {
            if (activityHistoryDisabled == disabled) {
                return false;
            }
            activityHistoryDisabled = disabled;
            if (disabled) {
                activityDaysByKind.clear();
            }
            return true;
        }

        /// @return whether this call changed the attachment held under `name`
        public boolean putAttachment(String name, JsonNode value) {
            return !value.equals(attachmentsByName.put(name, value));
        }

        /// @return the number of days deleted
        public int purgeActivityDaysBefore(LocalDate day) {
            int dayCountBefore = activityDaysByKind.size();
            activityDaysByKind.values().removeIf(activityDay -> activityDay.isBefore(day));
            return dayCountBefore - activityDaysByKind.size();
        }

        public UserRecord createRecord() {
            // noinspection DataFlowIssue — the map holds no null value, which the function's nullable parameter type cannot express
            return new UserRecord(profile.id(), activityDaysByKind, activityHistoryDisabled, Maps.transformValues(attachmentsByName, JsonNode::toString));
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
            // `activeIdentityRecordsByProvider` keeps the user's identity records while `deletedAt` is set, so `restore` can make the identities resolve to
            // the user again, as in `UserPersistenceImpl`.
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
