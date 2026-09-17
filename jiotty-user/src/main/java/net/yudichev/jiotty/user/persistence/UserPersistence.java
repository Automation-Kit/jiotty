package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Persistence gateway for user profiles and identities.
///
/// - Acts as the golden source for user profile data.
/// - Uses soft deletes; reads exclude deleted users and identities.
/// - Supports multiple provider identities per user.
///
/// TODO the change subscription here is an eventing bolt-on — no image on subscribe and no filter, so every subscriber
///  re-derives what this store already knew. It is to be merged with car-engine's `AdminUserDirectory`, which delivers
///  an image on subscribe and then deltas. See `workspace/USER-DIRECTORY-MERGE.md`.
public interface UserPersistence {
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

    /// Lists the active users whose last recorded activity ([#touchLastActive]) is strictly before `cutoff`, **least recently active first**, at most `limit`
    /// of them; soft-deleted users are excluded. The ordering is part of the contract: the instant it sorts by is not exposed on [UserProfile], so the result
    /// cannot be re-ordered by it afterwards, and the limit is only meaningful alongside it.
    ///
    /// @param cutoff the instant a user must have been active at or after to be left out of the result
    /// @param limit  the most rows to return, so a cutoff that matches everybody costs a bounded read rather than the whole table
    /// @throws IllegalArgumentException if `limit` is not positive
    CompletableFuture<List<UserProfile>> listInactiveSince(Instant cutoff, int limit);

    /// Soft-deletes the user ([#softDelete]), atomically and only if their last recorded activity is strictly before `cutoff`. The condition is evaluated and
    /// the delete applied indivisibly, so activity recorded at or after `cutoff` always wins over a concurrent call naming an earlier one.
    ///
    /// @param userId internal user id
    /// @param cutoff the instant a user must have been active at or after to be spared
    /// @return which of the four outcomes occurred; only [ConditionalSoftDeleteOutcome#STILL_ACTIVE] says the user's own activity spared them
    CompletableFuture<ConditionalSoftDeleteOutcome> softDeleteIfInactiveSince(String userId, Instant cutoff);

    /// Reports whether a user row exists for `userId`, regardless of soft-delete state; `false` once the user is hard-deleted.
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

    /// Soft-deletes the user and all linked identities. Idempotent: a no-op if the user is already soft-deleted.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> softDelete(String userId);

    /// Permanently removes the user and all their identity rows in one transaction. Intended for the erasure cascade after the grace period. Idempotent:
    /// completes normally even if the rows are already gone.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> hardDelete(String userId);

    /// Reverses [#softDelete]: clears the deletion mark on the user and on the identities that were soft-deleted together with it (matched by the shared
    /// deletion timestamp), reviving the account during the grace period. No-op if the user is not currently soft-deleted.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> restore(String userId);

    /// Calls the listener once a committed change has altered a user's profile, identity set or deletion state. Activity recorded by [#touchLastActive] is
    /// excluded, along with any statement that altered no row.
    ///
    /// **Late-joiner contract: no image is delivered on subscribe by design.** This is a delta stream whose image is [#listAllProfilesIgnoringDeletion], and
    /// registration is not atomic with that read: a change committing between the two is delivered before the image that already contains it, so a
    /// subscriber holds arriving changes until it has applied the image, then applies them.
    ///
    /// @return a handle that unsubscribes the listener
    Closeable subscribeToChanges(Consumer<? super UserChange> listener);

    /// A committed change to one user's record.
    ///
    /// @param userId internal user id of the user whose record changed
    /// @param state  the user's state as it was read back after the commit: present with an empty [UserProfileWithDeletion#deletedAt()] when active, present
    ///               with one when soft-deleted, and empty once the user is hard-deleted. A second change committing in between is read here instead, so this
    ///               is the state after the change rather than the state the change produced. It carries no identity data, so a change to the identity set
    ///               alone arrives with a `state` equal to the previous one; read [#listIdentities] to see what changed.
    record UserChange(String userId, Optional<UserProfileWithDeletion> state) {
        public UserChange {
            checkNotNull(userId, "userId");
            checkArgument(!userId.isBlank(), "userId must not be blank");
            checkNotNull(state, "state");
        }
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
