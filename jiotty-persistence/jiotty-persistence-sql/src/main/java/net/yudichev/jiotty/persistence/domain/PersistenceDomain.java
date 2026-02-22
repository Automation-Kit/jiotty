package net.yudichev.jiotty.persistence.domain;

import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Defines a versioned persistence domain (a set of related database entities).
///
/// @param name domain identifier used for entity name prefixes (letters, digits, and underscore only)
public record PersistenceDomain(String name) {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    public PersistenceDomain {
        checkNotNull(name, "domain name");
        checkArgument(!name.isBlank(), "domain name must not be blank");
        checkArgument(NAME_PATTERN.matcher(name).matches(), "domain name must match %s but was '%s'", NAME_PATTERN, name);
    }

    /// Returns a standard prefix for domain-owned entities (e.g. `users_`).
    public String prefix() {
        return name + '_';
    }
}
