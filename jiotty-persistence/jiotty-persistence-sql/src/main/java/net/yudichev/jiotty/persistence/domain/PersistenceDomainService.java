package net.yudichev.jiotty.persistence.domain;

import java.util.concurrent.CompletableFuture;

public interface PersistenceDomainService {
    /// Ensures the domain schema exists at the target version.
    ///
    /// @return future completing with `true` if the domain was freshly initialised (first time), `false` if it already existed or was migrated forward.
    CompletableFuture<Boolean> ensureDomainReady(PersistenceDomainConfig config);
}
