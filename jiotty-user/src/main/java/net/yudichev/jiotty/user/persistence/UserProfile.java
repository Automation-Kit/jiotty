package net.yudichev.jiotty.user.persistence;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// User profile as stored by the persistence layer.
///
/// @param id          internal user id
/// @param email       optional email (for example, when the identity provider does not expose it)
/// @param displayName optional user-visible name
/// @param timezone    user's preferred time zone
/// @param createdAt   creation time in storage
/// @param updatedAt   last update time in storage
public record UserProfile(String id,
                          Optional<String> email,
                          Optional<String> displayName,
                          ZoneId timezone,
                          Instant createdAt,
                          Instant updatedAt) {
    public UserProfile {
        checkNotNull(id, "id");
        checkArgument(!id.isBlank(), "id must not be blank");
        checkNotNull(email, "email");
        email.ifPresent(value -> checkArgument(!value.isBlank(), "email must not be blank"));
        checkNotNull(displayName, "displayName");
        displayName.ifPresent(value -> checkArgument(!value.isBlank(), "displayName must not be blank"));
        checkNotNull(timezone, "timezone");
        checkNotNull(createdAt, "createdAt");
        checkNotNull(updatedAt, "updatedAt");
    }
}
