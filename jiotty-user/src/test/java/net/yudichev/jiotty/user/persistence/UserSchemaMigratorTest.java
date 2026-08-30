package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserSchemaMigratorTest {
    private final UserSchemaMigrator migrator = new UserSchemaMigrator();

    /// The prefix placeholder is what the domain service expands, so a statement written without it would run against the wrong table — or none.
    @Test
    void makesTheAddressRequiredAtVersion2() {
        assertThat(migrator.getMigrationStatements(2)).singleElement()
                                                      .asString()
                                                      .contains("%DOMAIN_PREFIX%user")
                                                      .contains("email SET NOT NULL");
    }

    /// Every version up to [UserSchemaMigrator#BASE_SCHEMA_VERSION] must be reachable, or an app on an older store cannot be migrated forward at all — which
    /// is what makes bumping that constant without adding statements fail here rather than at a deployed environment's startup.
    @Test
    void knowsEveryVersionUpToTheOneTheInitStatementsCreate() {
        assertThat(IntStream.rangeClosed(2, UserSchemaMigrator.BASE_SCHEMA_VERSION).boxed())
                .allSatisfy(version -> assertThat(migrator.getMigrationStatements(version)).as("migration to v%d", version).isNotEmpty());
    }

    /// Loud rather than silent: a version this does not know means the caller asked for a schema this code cannot produce, and running nothing would leave the
    /// store recorded as migrated when it is not.
    @Test
    void rejectsAnUnknownVersion() {
        assertThatThrownBy(() -> migrator.getMigrationStatements(UserSchemaMigrator.BASE_SCHEMA_VERSION + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user schema");
    }
}
