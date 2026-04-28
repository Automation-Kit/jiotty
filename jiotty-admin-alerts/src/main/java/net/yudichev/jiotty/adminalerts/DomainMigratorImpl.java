package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;

import java.util.List;

final class DomainMigratorImpl implements PersistenceDomainMigrator {
    @Override
    public List<String> getMigrationStatements(int toVersion) {
        return List.of();
    }
}
