package net.yudichev.jiotty.user.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

    /// Resolves a *soft-deleted* user by provider identity, for the account-recovery / pending-deletion flow. The mirror image of [#getByIdentity]: it returns
    /// the user only when it IS soft-deleted (`deleted_at` set), together with that deletion instant, so the caller can apply its own grace-period policy.
    ///
    /// @param identity provider identity used for lookup
    /// @return empty if the identity is unknown or its user is active
    CompletableFuture<Optional<SoftDeletedUser>> findSoftDeletedByIdentity(UserIdentity identity);

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
}
