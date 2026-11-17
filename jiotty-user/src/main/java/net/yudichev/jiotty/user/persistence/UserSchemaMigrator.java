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
    public static final int BASE_SCHEMA_VERSION = 4;

    private static final String ALTER_USER_TABLE = "ALTER TABLE " + UserPersistenceImpl.USER_TABLE_TEMPLATE + ' ';

    /// - v2 makes the email address required, and fails on a store that holds a row without one.
    /// - v3 adds `last_active_at`. A default of the start of the current day, the resolution that every writer of the column uses, dates the rows already
    ///   there and is then dropped. Every insert supplies the value from the app's own clock, and a default left in place would silently replace that clock
    ///   with the database's.
    /// - v4 adds {@value UserPersistenceImpl#ACTIVITY_HISTORY_DISABLED_COLUMN}, false for every row already there, and the
    ///   {@value UserPersistenceImpl#ACTIVITY_DAY_TABLE} and {@value UserPersistenceImpl#ATTACHMENT_TABLE} tables.
    private static final Map<Integer, List<String>> STATEMENTS_BY_VERSION = ImmutableMap.of(
            2, ImmutableList.of(ALTER_USER_TABLE + "ALTER COLUMN email SET NOT NULL;"),
            3, ImmutableList.of(ALTER_USER_TABLE + "ADD COLUMN IF NOT EXISTS last_active_at timestamptz NOT NULL DEFAULT date_trunc('day', now());",
                                ALTER_USER_TABLE + "ALTER COLUMN last_active_at DROP DEFAULT;",
                                UserPersistenceImpl.LAST_ACTIVE_AT_INDEX_DDL),
            4, ImmutableList.of(ALTER_USER_TABLE + "ADD COLUMN IF NOT EXISTS " + UserPersistenceImpl.ACTIVITY_HISTORY_DISABLED_COLUMN_DDL + ';',
                                UserPersistenceImpl.ACTIVITY_DAY_TABLE_DDL,
                                UserPersistenceImpl.ATTACHMENT_TABLE_DDL));

    @Override
    public List<String> getMigrationStatements(int toVersion) {
        List<String> statements = STATEMENTS_BY_VERSION.get(toVersion);
        checkArgument(statements != null, "Don't know how to migrate the user schema to version %s", toVersion);
        return statements;
    }
}
