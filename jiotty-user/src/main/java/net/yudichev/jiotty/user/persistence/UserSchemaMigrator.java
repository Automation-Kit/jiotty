package net.yudichev.jiotty.user.persistence;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

/// Migrations for the base user schema, for an app whose own schema version is [#BASE_SCHEMA_VERSION].
///
/// An app that adds its own tables through the module's init statements versions them on the same counter, so it wraps this rather than replacing it: delegate
/// here for a version this knows, and answer for its own.
public final class UserSchemaMigrator implements PersistenceDomainMigrator {
    /// Version of the schema [UserPersistenceImpl]'s init statements create. An app supplying a lower number gets a store this code cannot read.
    public static final int BASE_SCHEMA_VERSION = 3;

    /// v2 requires the address, failing on a store holding a row without one rather than inventing a value for it. v3 adds `last_active_at`, defaulted only
    /// long enough to date the rows already there — to the day, as every writer of that column is — because every insert supplies it from the app's own
    /// clock and a lingering default would silently substitute the database's.
    private static final Map<Integer, List<String>> STATEMENTS_BY_VERSION = ImmutableMap.of(
            2, ImmutableList.of("ALTER TABLE %DOMAIN_PREFIX%user ALTER COLUMN email SET NOT NULL;"),
            3, ImmutableList.of("ALTER TABLE %DOMAIN_PREFIX%user ADD COLUMN IF NOT EXISTS last_active_at timestamptz NOT NULL "
                                + "DEFAULT date_trunc('day', now());",
                                "ALTER TABLE %DOMAIN_PREFIX%user ALTER COLUMN last_active_at DROP DEFAULT;",
                                UserPersistenceImpl.LAST_ACTIVE_AT_INDEX_DDL));

    @Override
    public List<String> getMigrationStatements(int toVersion) {
        List<String> statements = STATEMENTS_BY_VERSION.get(toVersion);
        checkArgument(statements != null, "Don't know how to migrate the user schema to version %s", toVersion);
        return statements;
    }
}
