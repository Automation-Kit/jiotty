package net.yudichev.jiotty.adminalerts;

import jakarta.annotation.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

/// Application-side admin alert API. Safe to call from any thread.
///
/// Operational model:
/// - [#raise(AdminAlertData)] is the only mutating verb. Each call appends an event (occurrence + description) to the bundle identified by
/// [AdminAlertData#key()] (derived from [AdminAlertData#title()], [AdminAlertData#severity()], [AdminAlertData#labels()]). The first call creates the bundle;
/// subsequent calls with the same key bump [AdminAlert#lastSeenAt()] / [AdminAlert#eventCount()] on the bundle and add a fresh event with the supplied
/// description — descriptions are *not* overwritten, they accumulate. To change [AdminAlertData#title()], [AdminAlertData#severity()], or
/// [AdminAlertData#labels()], resolve and re-raise — those fields participate in the key, so changing them produces a new bundle by construction.
/// - [#resolve(String, String)] is the application-driven resolution.
/// - [#resolveById(String,String,Optional)] is the operator-driven resolution from the alerting UI.
public interface AdminAlertService {
    /// Convenience overload of [#raise(AdminAlertSeverity, String, String)] for an exception-based alert. Also logs the failure.
    default String raise(AdminAlertSeverity severity, String title, Logger logger, Throwable e) {
        return raise(severity, title, logger, null, e);
    }

    /// Convenience overload that combines an explicit description prefix with an exception. The resulting alert description is `description + ": " +
    /// humanReadableMessage(e)` when `description` is non-null and non-blank; otherwise the bare `humanReadableMessage(e)`. Also logs the failure at the
    /// severity-matching level.
    default String raise(AdminAlertSeverity severity, String title, Logger logger, @Nullable String description, Throwable e) {
        var noDescription = description == null || description.isBlank();
        String combined = noDescription ? humanReadableMessage(e) : description + ": " + humanReadableMessage(e);
        logger.log(switch (severity) {
                       case WARNING -> Level.WARN;
                       case ERROR -> Level.ERROR;
                   },
                   "{}{}", title, noDescription ? "" : ": " + description, e);
        return raise(severity, title, combined);
    }

    /// Simplest convenience overload of [#raise(AdminAlertData)].
    default String raise(AdminAlertSeverity severity, String title) {
        return raise(severity, title, "");
    }

    /// Convenience overload of [#raise(AdminAlertData)] for the common "no labels, no fuss" call shape.
    default String raise(AdminAlertSeverity severity, String title, String description) {
        return raise(AdminAlertData.builder()
                                   .setSeverity(severity)
                                   .setTitle(title)
                                   .setDescription(description)
                                   .build());
    }

    /// Raises a new alert or, if an active alert with the same [AdminAlertData#key()] already exists, appends a new event to it (and bumps the heartbeat).
    ///
    /// @return the alert key
    String raise(AdminAlertData data);

    /// Server-driven resolution. Resolved-by is recorded as `"system"`. This method can only be called if the caller can guarantee that exactly the condition
    /// that caused the alert with the specified key is no longer a problem. If such guarantee cannot be made, the method should not be called: the alert will
    /// need to be left for the operator to resolve.
    ///
    /// @return a future of the id of the resolved alert, or empty if no active alert matches the given key
    CompletableFuture<Optional<String>> resolve(String key, String note);

    /// Operator-driven resolution by alert id (used by the HTTP resolve endpoint).
    CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note);

    /// Deletes resolved alerts whose [AdminAlert#resolvedAt()] is older than the given retention. Used by the periodic cleanup job, but exposed publicly so
    /// manual maintenance tooling can run a one-shot purge.
    ///
    /// @return the number of alerts deleted
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
