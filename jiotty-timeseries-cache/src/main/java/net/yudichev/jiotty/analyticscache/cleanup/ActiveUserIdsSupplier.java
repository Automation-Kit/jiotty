package net.yudichev.jiotty.analyticscache.cleanup;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/// Supplies the set of currently-active user ids. The cleanup job evicts user-scoped cache rows whose user id is not in this set.
///
/// @implSpec Implementations MUST return a snapshot; the cleanup job tolerates eventual consistency (a user freshly added between snapshot and cleanup will
/// simply not be evicted this round).
public interface ActiveUserIdsSupplier {
    CompletableFuture<Set<String>> get();
}
