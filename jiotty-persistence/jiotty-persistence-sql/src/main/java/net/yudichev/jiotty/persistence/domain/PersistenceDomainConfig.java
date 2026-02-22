package net.yudichev.jiotty.persistence.domain;

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Configuration for a [PersistenceDomain].
///
/// @param domain         domain describing the persistence entities
/// @param schemaVersion  target schema version
/// @param initStatements SQL statements used for initialisation; statements may include `%DOMAIN%` and `%DOMAIN_PREFIX%` placeholders
/// @param migrator       provides migration statements between versions
public record PersistenceDomainConfig(PersistenceDomain domain,
                                      int schemaVersion,
                                      List<String> initStatements,
                                      PersistenceDomainMigrator migrator) {
    public PersistenceDomainConfig {
        checkNotNull(domain, "domain");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        initStatements = List.copyOf(checkNotNull(initStatements, "initStatements"));
        checkNotNull(migrator, "migrator");
    }
}
