package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.ZoneId;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// User profile values.
///
/// @param email       the address the account is reached at; required and never blank
/// @param displayName optional user-visible name
/// @param timezone    user's preferred time zone
/// @implNote renders with [#email()] and [#displayName()] redacted, matching [UserProfile].
public record UserProfileInput(String email,
                               Optional<String> displayName,
                               ZoneId timezone) implements StringFormattable {
    public UserProfileInput {
        checkNotNull(email, "email");
        checkArgument(!email.isBlank(), "email must not be blank");
        checkNotNull(displayName, "displayName");
        displayName.ifPresent(value -> checkArgument(!value.isBlank(), "displayName must not be blank"));
        checkNotNull(timezone, "timezone");
    }

    @Override
    public String toString() {
        return toString(96);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "UserProfileInput[");
        UserProfile.appendProfileTail(appendable, email, displayName, timezone);
    }
}
