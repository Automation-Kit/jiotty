package net.yudichev.jiotty.user.persistence;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// User profile as stored by the persistence layer.
///
/// @param id          internal user id
/// @param email       optional email (for example, when the identity provider does not expose it)
/// @param displayName optional user-visible name
/// @param timezone    user's preferred time zone
/// @param createdAt   creation time in storage
/// @param updatedAt   last update time in storage
/// @implNote renders the id, a redacted [#email()] and [#displayName()], and the timezone. [#id()] stays whole: it is the pseudonymous id the `[{}]`
/// log-prefix convention already relies on.
public record UserProfile(String id,
                          Optional<String> email,
                          Optional<String> displayName,
                          ZoneId timezone,
                          Instant createdAt,
                          Instant updatedAt) implements StringFormattable {
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

    @Override
    public String toString() {
        return toString(96);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "UserProfile[id=");
        Append.to(appendable, id);
        Append.to(appendable, ", ");
        appendProfileTail(appendable, email, displayName, timezone);
    }

    /// Appends the redacted `email`, `displayName` and `timezone` tail that [UserProfileInput] renders identically, so a field added to one cannot go
    /// missing from the other.
    static void appendProfileTail(Appendable appendable, Optional<String> email, Optional<String> displayName, ZoneId timezone) {
        Append.to(appendable, "email=");
        appendRedacted(appendable, email);
        Append.to(appendable, ", displayName=");
        appendRedacted(appendable, displayName);
        Append.to(appendable, ", timezone=");
        Append.to(appendable, timezone.getId());
        Append.to(appendable, ']');
    }
}
