package net.yudichev.jiotty.persistence.domain;

import java.util.List;

public interface PersistenceDomainMigrator {
    PersistenceDomainMigrator FAIL_ON_MIGRATION = toVersion -> {
        throw new IllegalArgumentException("Don't know how to migrate to version " + toVersion);
    };

    List<String> getMigrationStatements(int toVersion);
}
