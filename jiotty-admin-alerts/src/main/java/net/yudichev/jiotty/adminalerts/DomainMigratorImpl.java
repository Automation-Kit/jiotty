package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;

import java.util.List;

final class DomainMigratorImpl implements PersistenceDomainMigrator {
    @Override
    public List<String> getMigrationStatements(int toVersion) {
        return switch (toVersion) {
            case 2 ->
                // No v1 data is worth preserving in any environment that has it (local/UAT). Drop the v1 alert table + severity type
                // and re-run the v2 init to land on the bundle+events shape.
                    ImmutableList.<String>builder()
                                 .add("DROP TABLE IF EXISTS %DOMAIN_PREFIX%alert_event CASCADE")
                                 .add("DROP TABLE IF EXISTS %DOMAIN_PREFIX%alert CASCADE")
                                 .add("DROP TYPE IF EXISTS %DOMAIN_PREFIX%severity")
                                 .addAll(AdminAlertSchema.INIT_STATEMENTS)
                                 .build();
            default -> throw new IllegalStateException("Unsupported migration to version " + toVersion);
        };
    }
}
