package net.yudichev.jiotty.user.persistence;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/// A user profile together with its soft-delete state, as returned by [UserPersistence#listAllProfilesIgnoringDeletion].
///
/// @param profile   the user profile
/// @param deletedAt soft-delete time, or empty when the user is active
public record UserProfileWithDeletion(UserProfile profile, Optional<Instant> deletedAt) {
    public UserProfileWithDeletion {
        checkNotNull(profile, "profile");
        checkNotNull(deletedAt, "deletedAt");
    }
}
