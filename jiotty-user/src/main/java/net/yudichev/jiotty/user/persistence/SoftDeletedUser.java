package net.yudichev.jiotty.user.persistence;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;

/// A soft-deleted user resolved by provider identity: the stored [UserProfile] plus the instant it was soft-deleted. The persistence layer reports the raw
/// `deleted_at` only; the caller applies its own recovery-window / grace policy.
public record SoftDeletedUser(UserProfile profile, Instant deletedAt) {
    public SoftDeletedUser {
        checkNotNull(profile, "profile");
        checkNotNull(deletedAt, "deletedAt");
    }
}
