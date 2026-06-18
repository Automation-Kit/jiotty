package net.yudichev.jiotty.user.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;

/// Persistence gateway for user profiles and identities.
///
/// - Acts as the golden source for user profile data.
/// - Uses soft deletes; reads exclude deleted users and identities.
/// - Supports multiple provider identities per user.
public interface UserPersistence {
    /// Returns an existing user for `identity`, or creates a new user atomically with `profile`.
    ///
    /// If concurrent creation results in a uniqueness conflict, the existing user for the identity is returned.
    ///
    /// @param identity provider identity used for lookup and creation
    /// @param profile  initial profile details for a newly created user
    /// @return the existing or newly created user profile
    CompletableFuture<UserProfile> getOrCreateByIdentity(UserIdentity identity, UserProfileInput profile);

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

    /// Lists active user profiles (deleted users are excluded).
    CompletableFuture<List<UserProfile>> listAllProfiles();

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
