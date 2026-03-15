package net.yudichev.jiotty.user.persistence;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;

/// Linked identity with timestamps.
///
/// @param identity  provider identity
/// @param createdAt creation time in storage
/// @param updatedAt last update time in storage
public record UserIdentityRecord(UserIdentity identity,
                                 Instant createdAt,
                                 Instant updatedAt) {
    public UserIdentityRecord {
        checkNotNull(identity, "identity");
        checkNotNull(createdAt, "createdAt");
        checkNotNull(updatedAt, "updatedAt");
    }
}
