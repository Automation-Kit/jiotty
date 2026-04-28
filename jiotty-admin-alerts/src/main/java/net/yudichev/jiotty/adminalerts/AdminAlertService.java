package net.yudichev.jiotty.adminalerts;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/// Application-side admin alert API. Backed by Postgres; safe to call from any thread.
///
/// Operational model:
/// - [#raise] is the main call. The first call inserts a row; repeated calls with the same `dedupKey` while the alert is still active only bump `lastSeenAt`
/// and `updateCount` and do **not** overwrite description or labels.
/// - [#update] is the way to change description or labels on an active alert. Title and severity are immutable post-raise — to change them, resolve and
/// re-raise.
/// - [#resolve] is the application-driven resolution.
/// - [#resolveById] is the operator-driven resolution from the alerting UI.
public interface AdminAlertService {
    /// Raises a new alert or, if an active alert with the same `dedupKey` already exists, marks it as still firing.
    ///
    /// @return a future of the alert id (the existing id when re-firing, a fresh id when new)
    CompletableFuture<String> raise(AdminAlertData data);

    /// Updates an active alert with the given `dedupKey`. Returns the id of the affected row, or empty if no active alert matches.
    CompletableFuture<Optional<String>> update(String dedupKey, AdminAlertUpdate update);

    /// Server-driven resolution. `resolvedBy` is conventionally `"system"`. Returns the id of the resolved row, or empty if no active alert matches.
    CompletableFuture<Optional<String>> resolve(String dedupKey, Optional<String> note);

    /// Operator-driven resolution by row id (used by the HTTP resolve endpoint).
    CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note);

    /// Looks up a single alert by id. Useful for follow-up actions and tooling.
    CompletableFuture<Optional<AdminAlert>> getById(String alertId);

    /// Deletes resolved alerts whose `resolvedAt` is older than `retention`. Used by the periodic cleanup job, but exposed publicly so manual maintenance
    /// tooling can run a one-shot purge.
    ///
    /// @return the number of rows deleted
    CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention);

    enum ResolveByIdOutcome {
        /// The alert existed, was active, and has just been resolved.
        RESOLVED,
        /// The alert exists but was already resolved before this call. No state change.
        ALREADY_RESOLVED,
        /// No alert with this id exists.
        UNKNOWN
    }
}
