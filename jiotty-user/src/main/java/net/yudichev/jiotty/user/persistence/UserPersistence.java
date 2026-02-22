package net.yudichev.jiotty.user.persistence;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
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

    /// Returns the active user profile by id.
    ///
    /// @param userId internal user id
    /// @return empty if the user does not exist or is deleted
    CompletableFuture<Optional<UserProfile>> getById(UUID userId);

    /// Lists active user profiles (deleted users are excluded).
    CompletableFuture<List<UserProfile>> listAllProfiles();

    /// Updates the active user's profile fields in one transaction.
    ///
    /// @param userId internal user id
    /// @param update new profile values (email may be null to clear it)
    /// @return the updated user profile
    CompletableFuture<UserProfile> updateProfile(UUID userId, UserProfileUpdate update);

    /// Links a provider identity to an existing user.
    ///
    /// If the identity is already linked to another user, the operation fails.
    ///
    /// @param userId   internal user id
    /// @param identity provider identity to link
    CompletableFuture<Void> linkIdentity(UUID userId, UserIdentity identity);

    /// Lists active identities for a user.
    ///
    /// @param userId internal user id
    /// @return active identity records (deleted identities are excluded)
    CompletableFuture<List<UserIdentityRecord>> listIdentities(UUID userId);

    /// Soft-deletes the user and all linked identities.
    ///
    /// @param userId internal user id
    CompletableFuture<Void> softDelete(UUID userId);

    /// Provider identity used to authenticate a user.
    ///
    /// @param provider       provider identifier (for example `firebase`, `google.com`, `apple.com`)
    /// @param providerUserId provider-specific unique user id
    record UserIdentity(String provider, String providerUserId) {
        public UserIdentity {
            checkNotNull(provider, "provider");
            checkNotNull(providerUserId, "providerUserId");
            checkArgument(!provider.isBlank(), "provider must not be blank");
            checkArgument(!providerUserId.isBlank(), "providerUserId must not be blank");
        }
    }

    /// User profile as stored by the persistence layer.
    ///
    /// @param id          internal user id
    /// @param email       optional email (for example, when the identity provider does not expose it)
    /// @param displayName user-visible name
    /// @param timezone    user's preferred time zone
    /// @param createdAt   creation time in storage
    /// @param updatedAt   last update time in storage
    record UserProfile(UUID id,
                       @Nullable String email,
                       String displayName,
                       ZoneId timezone,
                       Instant createdAt,
                       Instant updatedAt) {
        public UserProfile {
            checkNotNull(id, "id");
            if (email != null) {
                checkArgument(!email.isBlank(), "email must not be blank");
            }
            checkNotNull(displayName, "displayName");
            checkArgument(!displayName.isBlank(), "displayName must not be blank");
            checkNotNull(timezone, "timezone");
            checkNotNull(createdAt, "createdAt");
            checkNotNull(updatedAt, "updatedAt");
        }
    }

    /// Initial profile details for a newly created user.
    ///
    /// @param email       optional email (may be null when not available)
    /// @param displayName user-visible name
    /// @param timezone    user's preferred time zone
    record UserProfileInput(@Nullable String email,
                            String displayName,
                            ZoneId timezone) {
        public UserProfileInput {
            if (email != null) {
                checkArgument(!email.isBlank(), "email must not be blank");
            }
            checkNotNull(displayName, "displayName");
            checkArgument(!displayName.isBlank(), "displayName must not be blank");
            checkNotNull(timezone, "timezone");
        }
    }

    /// Updated profile values for an existing user.
    ///
    /// @param email       optional email (null clears the stored value)
    /// @param displayName user-visible name
    /// @param timezone    user's preferred time zone
    record UserProfileUpdate(@Nullable String email,
                             String displayName,
                             ZoneId timezone) {
        public UserProfileUpdate {
            if (email != null) {
                checkArgument(!email.isBlank(), "email must not be blank");
            }
            checkNotNull(displayName, "displayName");
            checkArgument(!displayName.isBlank(), "displayName must not be blank");
            checkNotNull(timezone, "timezone");
        }
    }

    /// Linked identity with timestamps.
    ///
    /// @param identity  provider identity
    /// @param createdAt creation time in storage
    /// @param updatedAt last update time in storage
    record UserIdentityRecord(UserIdentity identity,
                              Instant createdAt,
                              Instant updatedAt) {
        public UserIdentityRecord {
            checkNotNull(identity, "identity");
            checkNotNull(createdAt, "createdAt");
            checkNotNull(updatedAt, "updatedAt");
        }
    }
}
