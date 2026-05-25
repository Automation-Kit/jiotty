package net.yudichev.jiotty.timeseriescache;

import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;

import java.util.List;

final class DomainMigratorImpl implements PersistenceDomainMigrator {
    @Override
    public List<String> getMigrationStatements(int toVersion) {
        // v1 is the only version; no migrations defined yet. Future increments should add cases here.
        throw new IllegalStateException("Unsupported migration to version " + toVersion);
    }
}
