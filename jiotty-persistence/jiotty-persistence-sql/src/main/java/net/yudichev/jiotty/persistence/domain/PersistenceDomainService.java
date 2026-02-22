package net.yudichev.jiotty.persistence.domain;

import java.util.concurrent.CompletableFuture;

public interface PersistenceDomainService {
    CompletableFuture<Void> ensureDomainReady(PersistenceDomainConfig config);
}
