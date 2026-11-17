package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Persistence gateway for user profiles and identities.
///
/// - Acts as the golden source for user profile data.
/// - Uses soft deletes; reads exclude deleted users and identities.
/// - Supports multiple provider identities per user.
/// - Publishes its state as an image followed by each committed change, in commit order and serialised with its writes.
public interface UserPersistence {
    /// The maximum length, in characters, of a JSON document that [#putAttachment] stores.
    int MAX_ATTACHMENT_LENGTH = 8192;

    /// Returns an existing user for `identity`, or creates a new user atomically with `profile`.
    ///
    /// If concurrent creation results in a uniqueness conflict on the identity, the existing user for it is returned.
    ///
    /// @param identity provider identity used for lookup and creation
    /// @param profile  initial profile details for a newly created user
    /// @return [UserCreationResult.Resolved] with the existing or newly created user profile, or [UserCreationResult.EmailAlreadyInUse] if `profile.email()`
    /// already belongs to a different user
    CompletableFuture<UserCreationResult> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile);

    /// Returns the active user profile by identity.
    ///
    /// @return empty if the identity is not linked or linked to a deleted user
    CompletableFuture<Optional<UserProfile>> getByIdentity(UserIdentity identity);

    /// Resolves a user by provider identity in a single lookup, reporting whether the linked user is active, soft-deleted, or absent. Unlike [#getByIdentity]
    /// (which hides soft-deleted users), this differentiates the three outcomes so the caller can drive an account-recovery / pending-deletion flow off one
    /// query.
    ///
    /// @param identity provider identity used for lookup
    /// @return [IdentityResolution.Active] if the identity links to an active user, [IdentityResolution.SoftDeleted] if it links to a soft-deleted user, or
    /// [IdentityResolution.Absent] if the identity is unknown
    CompletableFuture<IdentityResolution> resolveByIdentity(UserIdentity identity);

    /// Returns the active user profile by id.
    ///
    /// @param userId internal user id
    /// @return empty if the user does not exist or is deleted
    CompletableFuture<Optional<UserProfile>> getById(String userId);

    /// Returns the user profile by id regardless of soft-delete state — unlike [#getById], which excludes soft-deleted users.
    ///
    /// @param userId internal user id
    /// @return empty once the user is hard-deleted
    CompletableFuture<Optional<UserProfile>> getByIdIgnoringDeletion(String userId);

    /// Lists active user profiles (deleted users are excluded).
    CompletableFuture<List<UserProfile>> listAllProfiles();

    /// Lists all user profiles regardless of soft-delete state — unlike [#listAllProfiles], which excludes soft-deleted users — reporting each user's
    /// soft-delete time.
    CompletableFuture<List<UserProfileWithDeletion>> listAllProfilesIgnoringDeletion();

    /// Records the user's most recent activity, monotonically and whatever their deletion state. The value is stored as supplied, at whatever resolution it
    /// carries.
    ///
    /// @param userId   internal user id
    /// @param activeAt when the activity happened
    /// @return `true` if the stored instant moved, `false` if it was already at or past `activeAt`, or the user is absent
    CompletableFuture<Boolean> touchLastActive(String userId, Instant activeAt);

    /// Records `day` as a day on which the user had activity of kind `kind`, unless [#setActivityHistoryDisabled] has disabled the user's activity history.
    /// In the same transaction, advances the user's last-active instant ([#touchLastActive]) to the start of `day` in UTC, if that instant is earlier. The
    /// user's deletion state makes no difference.
    ///
    /// @param userId internal user id
    /// @param kind   the kind of activity, an arbitrary name
    /// @param day    the UTC day on which the activity happened
    CompletableFuture<ActivityRecordOutcome> recordActivity(String userId, String kind, LocalDate day);

    /// Switches the recording of the user's activity days ([#recordActivity]) off or on. Switching it off deletes the days already recorded, in the same
    /// transaction.
    ///
    /// @param userId   internal user id
    /// @param disabled `true` to stop recording activity days, `false` to resume
    /// @return `true` if the switch is stored, or `false` if no user with id `userId` exists. A soft-deleted user exists until [#hardDelete] removes it.
    CompletableFuture<Boolean> setActivityHistoryDisabled(String userId, boolean disabled);

    /// Stores `json` as the user's attachment called `name`, replacing the value already under that name. A read returns equivalent JSON, which can differ
    /// from `json` in whitespace and key order.
    ///
    /// Every subscriber to [#subscribe(LocalDate, UserRecordListener)] receives every user's attachments and each change to them. If no subscriber needs the
    /// value, use [VarStore#saveValue] instead. If the value is a secret, use [VarStore#saveValueEncrypted] instead.
    ///
    /// @param userId internal user id
    /// @param name   the attachment's name, an arbitrary string that identifies it among the user's attachments
    /// @param json   a JSON document
    /// @return `true` if the attachment is stored, or `false` if no user with id `userId` exists. A soft-deleted user exists until [#hardDelete] removes it.
    /// @throws IllegalArgumentException if `json` is longer than [#MAX_ATTACHMENT_LENGTH] characters
    CompletableFuture<Boolean> putAttachment(String userId, String name, String json);

    /// Checks `json` against the length limit of [#putAttachment].
    ///
    /// @param name the attachment's name, which the exception's message carries
    /// @throws IllegalArgumentException if `json` is longer than [#MAX_ATTACHMENT_LENGTH] characters
    static void checkAttachmentLength(String name, String json) {
        checkArgument(json.length() <= MAX_ATTACHMENT_LENGTH,
                      "attachment %s is %s characters long, over the %s allowed",
                      name,
                      json.length(),
                      MAX_ATTACHMENT_LENGTH);
    }

    /// Deletes every user's activity days before `day`.
    ///
    /// @return the number of days deleted
    CompletableFuture<Integer> purgeActivityDaysBefore(LocalDate day);

    /// Reads the user's [UserRecord], including every activity day recorded for the user.
    ///
    /// @param userId internal user id
    /// @return the user's record, or empty if no user with id `userId` exists. A soft-deleted user exists until [#hardDelete] removes it.
    CompletableFuture<Optional<UserRecord>> readRecord(String userId);

    /// Lists the active users whose last recorded activity ([#touchLastActive]) is strictly before `cutoff`, **least recently active first**, at most `limit`
    /// of them; soft-deleted users are excluded. The ordering is part of the contract: the instant it sorts by is not exposed on [UserProfile], so the result
    /// cannot be re-ordered by it afterwards, and the limit is only meaningful alongside it.
    ///
    /// @param cutoff the instant a user must have been active at or after to be left out of the result
    /// @param limit  the most users to return, which bounds the read when `cutoff` matches every user
    /// @throws IllegalArgumentException if `limit` is not positive
    CompletableFuture<List<UserProfile>> listInactiveSince(Instant cutoff, int limit);

    /// Soft-deletes the user ([#softDelete]), atomically and only if their last recorded activity is strictly before `cutoff`. The condition is evaluated and
    /// the delete applied indivisibly, so activity recorded at or after `cutoff` always wins over a concurrent call naming an earlier one.
    ///
    /// @param userId internal user id
    /// @param cutoff the instant a user must have been active at or after to be spared
    /// @return which of the four outcomes occurred; only [ConditionalSoftDeleteOutcome#STILL_ACTIVE] says the user's own activity spared them
    CompletableFuture<ConditionalSoftDeleteOutcome> softDeleteIfInactiveSince(String userId, Instant cutoff);

    /// Reports whether a user with id `userId` exists, soft-deleted or not; `false` once [#hardDelete] has removed the user.
    ///
    /// @param userId internal user id
    CompletableFuture<Boolean> existsIgnoringDeletion(String userId);

    /// Updates the active user's profile fields in one transaction.
    ///
    /// @param userId  internal user id
    /// @param profile new profile values
    /// @return the updated user profile
    CompletableFuture<UserProfile> updateProfile(String userId, UserProfileInput profile);

    /// Replaces the user's complete active identity set with `identities`.
    ///
    /// Existing identities for providers not present in `identities` are soft-deleted. Existing identities for matching providers are revived or updated to the
    /// supplied provider user id. If any supplied identity is already linked to another user, the operation fails.
    ///
    /// @param userId     internal user id
    /// @param identities complete desired active identity set for the user
    CompletableFuture<Void> updateAllIdentities(String userId, List<UserIdentity> identities);

    /// Lists active identities for a user.
    ///
    /// @param userId internal user id
    /// @return active identity records (deleted identities are excluded)
    CompletableFuture<List<UserIdentityRecord>> listIdentities(String userId);

    /// Lists a user's identities regardless of soft-delete state — unlike [#listIdentities], which returns nothing for a soft-deleted user. Lets a caller
    /// read the identities of a user that has been soft-deleted ([#softDelete]) but not yet hard-deleted ([#hardDelete]).
    CompletableFuture<List<UserIdentityRecord>> listIdentitiesIgnoringDeletion(String userId);

    /// Soft-deletes the user and all linked identities. Idempotent: a no-op if the user is already soft-deleted, though the user's state is delivered to
    /// subscribers on every call, so a retry repeats a delivery that a failed commit lost.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> softDelete(String userId);

    /// Permanently removes the user with all their identities, activity days and attachments in one transaction. Idempotent: completes normally even if
    /// the user is already removed, and delivers the removal to subscribers again.
    ///
    /// @param userId internal user id
    /// @return a future that completes once the user is removed and every [UserStateListener] and [UserRecordListener] subscribed at the commit has applied the
    ///         removal. It completes exceptionally if one of those listeners fails to apply the removal.
    CompletableFuture<Void> hardDelete(String userId);

    /// Reverses [#softDelete]: clears the deletion mark on the user and on the identities that were soft-deleted together with it (matched by the shared
    /// deletion timestamp), reviving the account during the grace period. No-op if the user is not currently soft-deleted, though the user's state is
    /// delivered to subscribers on every call.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> restore(String userId);

    /// Delivers every user's state, soft-deleted users included, then each committed change to it, one at a time and in commit order. **A listener must
    /// return without blocking**, because the writes wait for it.
    ///
    /// @return a handle that unsubscribes the listener
    Closeable subscribe(UserStateListener listener);

    /// Delivers what `identity` resolves to, as [#resolveByIdentity] answers it, then each committed change to that answer, one at a time and in commit
    /// order. **A listener must return without blocking**, as for [#subscribe(UserStateListener)].
    ///
    /// @return a handle that unsubscribes the listener
    Closeable subscribe(UserIdentity identity, IdentityResolutionListener listener);

    /// Delivers every user's [UserRecord], then each committed change to a user's record, one delivery at a time and in commit order. A write that leaves a
    /// record unchanged delivers nothing. **A listener must return without blocking**, as for [#subscribe(UserStateListener)].
    ///
    /// @param earliestDay a delivered record holds no activity day before this day
    /// @return a handle that unsubscribes the listener
    Closeable subscribe(LocalDate earliestDay, UserRecordListener listener);

    /// A listener for [#subscribe(LocalDate, UserRecordListener)]'s deliveries.
    interface UserRecordListener {
        /// Invoked once, before any other delivery, with every user's record as it was when the subscription started, soft-deleted users included.
        void onImage(List<UserRecord> records);

        /// Invoked with the whole record of one user after a committed write changed it.
        void onChanged(UserRecord record);

        /// Invoked after a committed write deleted every user's activity days before `day`.
        void onDaysPurged(LocalDate day);

        /// Invoked after a committed [#hardDelete] of the user `userId`, including a repeated one for a user who is already gone.
        ///
        /// @return a future that the future returned by [#hardDelete] waits for: that future completes only after this one completes, and completes
        ///         exceptionally if this one does
        /// @implSpec The returned future must complete once the removal has been applied.
        CompletableFuture<?> onRemoved(String userId);

        /// Invoked when the subscription ends, after which this listener receives no further delivery. A new subscription delivers a current image. The
        /// subscription ends when:
        ///
        /// - the image could not be read;
        /// - this listener threw on a delivery, and so missed a change;
        /// - the commit of [#purgeActivityDaysBefore] reported a failure;
        /// - the commit of another write reported a failure, and the user's record could not be read after it.
        void onSubscriptionFailed(Throwable failure);
    }

    /// A listener for [#subscribe(UserStateListener)]'s deliveries.
    interface UserStateListener {
        /// Every user when the subscription started, delivered once and before any change.
        void onImage(List<UserProfileWithDeletion> users);

        /// A user created, changed, soft-deleted or restored, as the committing write left them. A repeated deletion or restore delivers the state it
        /// found unchanged, so the same state can arrive twice.
        void onChanged(UserProfileWithDeletion user);

        /// Invoked after a committed [#hardDelete] of the user `userId`, including a repeated one for a user who is already gone.
        ///
        /// @return a future that the future returned by [#hardDelete] waits for: that future completes only after this one completes, and completes
        ///         exceptionally if this one does
        /// @implSpec The returned future must complete once the removal has been applied.
        CompletableFuture<?> onRemoved(String userId);

        /// The subscription has ended, and nothing further arrives: the image could not be read, or this listener threw on a delivery and so missed a change.
        void onSubscriptionFailed(Throwable failure);
    }

    /// A listener for [#subscribe(UserIdentity, IdentityResolutionListener)]'s deliveries.
    interface IdentityResolutionListener {
        /// What the identity resolves to: first its resolution when the subscription started, then each different one after it.
        void onResolution(IdentityResolution resolution);

        /// The subscription has ended, and nothing further arrives: the first resolution could not be read, or this listener threw on a delivery.
        void onSubscriptionFailed(Throwable failure);
    }

    /// The outcome of [#getOrCreateByIdentity].
    sealed interface UserCreationResult permits UserCreationResult.Resolved, UserCreationResult.EmailAlreadyInUse {
        /// The identity resolved to an existing user, or a new user was created for it.
        ///
        /// @param profile the resolved user, whether this call created it or found it already there
        /// @param created `true` only when this call performed the insert. Two concurrent calls for the same brand-new identity both resolve, and exactly one
        ///                of them created — so anything that must happen once per account, rather than once per resolution, keys off this rather than off
        ///                having reached this variant.
        record Resolved(UserProfile profile, boolean created) implements UserCreationResult {
            public Resolved {
                checkNotNull(profile, "profile");
            }
        }

        /// No user could be created because the requested email already belongs to a different user. Clearing it needs an operator to reconcile the identity
        /// provider with this store, so surface it as its own state rather than retrying.
        final class EmailAlreadyInUse implements UserCreationResult {
            public static final EmailAlreadyInUse INSTANCE = new EmailAlreadyInUse();

            private EmailAlreadyInUse() {
            }
        }
    }

    /// The outcome of [#resolveByIdentity].
    sealed interface IdentityResolution permits IdentityResolution.Active, IdentityResolution.SoftDeleted, IdentityResolution.Absent {
        /// The resolution of an identity linked to `profile`'s user.
        ///
        /// @param softDeleted whether that user is soft-deleted
        static IdentityResolution createLinked(UserProfile profile, boolean softDeleted) {
            return softDeleted ? new SoftDeleted(profile) : new Active(profile);
        }

        /// The identity links to an active (not soft-deleted) user.
        record Active(UserProfile profile) implements IdentityResolution {
            public Active {
                checkNotNull(profile, "profile");
            }
        }

        /// The identity links to a soft-deleted user (within or beyond any recovery window — the caller applies its own policy).
        record SoftDeleted(UserProfile profile) implements IdentityResolution {
            public SoftDeleted {
                checkNotNull(profile, "profile");
            }
        }

        /// The identity is not linked to any user.
        final class Absent implements IdentityResolution {
            public static final Absent INSTANCE = new Absent();

            private Absent() {
            }
        }
    }
}
