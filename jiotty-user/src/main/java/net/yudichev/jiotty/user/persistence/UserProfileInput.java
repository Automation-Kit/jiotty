package net.yudichev.jiotty.user.persistence;

import java.time.ZoneId;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// User profile values.
///
/// @param email       optional email
/// @param displayName optional user-visible name
/// @param timezone    user's preferred time zone
public record UserProfileInput(Optional<String> email,
                               Optional<String> displayName,
                               ZoneId timezone) {
    public UserProfileInput {
        checkNotNull(email, "email");
        email.ifPresent(value -> checkArgument(!value.isBlank(), "email must not be blank"));
        checkNotNull(displayName, "displayName");
        displayName.ifPresent(value -> checkArgument(!value.isBlank(), "displayName must not be blank"));
        checkNotNull(timezone, "timezone");
    }
}
