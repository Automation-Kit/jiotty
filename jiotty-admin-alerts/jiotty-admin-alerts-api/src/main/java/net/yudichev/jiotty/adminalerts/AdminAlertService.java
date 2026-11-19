package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

/// A service that raises, resolves and deletes [AdminAlert]s. Safe to call from any thread.
///
/// An alert is active from the [#raise(AdminAlertData)] that creates it until [#resolve(String, String)] or [#resolveById(String, String, Optional)]
/// resolves it.
public interface AdminAlertService {
    /// Raises and logs an alert as [#raise(AdminAlertSeverity, String, Logger, Optional, Optional)] does, with `e` as the cause and an absent description.
    default String raise(AdminAlertSeverity severity, String title, Logger logger, Throwable e) {
        return raise(severity, title, logger, null, e);
    }

    /// Raises and logs an alert as [#raise(AdminAlertSeverity, String, Logger, Optional, Optional)] does, with `e` as the cause.
    ///
    /// @param description the part of the alert's description that precedes the message of `e`; `null` when the alert's description is that message
    default String raise(AdminAlertSeverity severity, String title, Logger logger, @Nullable String description, Throwable e) {
        return raise(severity, title, logger, Optional.ofNullable(description), Optional.of(e));
    }

    /// Raises an alert as [#raise(AdminAlertData)] does, and logs it. The alert's description is:
    /// - `description`, `": "` and the [message][HumanReadableExceptionMessage#humanReadableMessage] of `cause`, in that order, when both are present;
    /// - whichever of the two is present, when the other is absent;
    /// - empty, when both are absent.
    ///
    /// The log entry is written to `logger` at [Level#WARN] for [AdminAlertSeverity#WARNING] and at [Level#ERROR] for [AdminAlertSeverity#ERROR], and
    /// contains `title`, `description` and `cause`.
    ///
    /// @param description the alert's description, or the part of it that precedes the message of `cause`; a blank value is treated as absent
    /// @return the alert's [AdminAlertData#key()]
    default String raise(AdminAlertSeverity severity, String title, Logger logger, Optional<String> description, Optional<Throwable> cause) {
        Optional<String> prefix = description.filter(text -> !text.isBlank());
        String alertDescription = cause.map(e -> prefix.map(text -> text + ": ").orElse("") + humanReadableMessage(e)).orElse(prefix.orElse(""));
        logger.log(logLevelOf(severity), "{}{}", title, prefix.map(text -> ": " + text).orElse(""), cause.orElse(null));
        return raise(severity, title, alertDescription);
    }

    private static Level logLevelOf(AdminAlertSeverity severity) {
        return switch (severity) {
            case WARNING -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }

    /// Raises an alert with the given labels, as [#raise(AdminAlertData)] does, and logs it. The alert's description is `description`, `": "` and the
    /// [message][HumanReadableExceptionMessage#humanReadableMessage] of `e`, in that order. The log entry is written to `logger` at the level at which
    /// [#raise(AdminAlertSeverity, String, Logger, Optional, Optional)] logs, and contains the values of `labels`, `title`, `description` and `e`.
    ///
    /// @return the alert's [AdminAlertData#key()]
    default String raise(AdminAlertSeverity severity, String title, Logger logger, String description, Throwable e, Map<String, String> labels) {
        logger.log(logLevelOf(severity), "[{}] {}: {}", labels.values(), title, description, e);
        return raise(AdminAlertData.builder()
                                   .setSeverity(severity)
                                   .setTitle(title)
                                   .setDescription(description + ": " + humanReadableMessage(e))
                                   .setLabels(labels)
                                   .build());
    }

    /// Creates the callback that [#alertOnFailure(AdminAlertSeverity, String, Logger, String)] creates for a `null` description. If the failure needs a log
    /// entry and no alert, use [CompletableFutures#logErrorOnFailure] instead.
    ///
    /// @param <T> the type of the value that the future completes with
    default <T> BiConsumer<T, Throwable> alertOnFailure(AdminAlertSeverity severity, String title, Logger logger) {
        return alertOnFailure(severity, title, logger, null);
    }

    /// Creates a callback that raises and logs an alert when a future fails.
    ///
    /// @param <T>         the type of the value that the future completes with
    /// @param description the part of the alert's description that precedes the failure's message; `null` when the alert's description is that message
    /// @return a callback for [CompletableFuture#whenComplete] that passes a failure to [#raise(AdminAlertSeverity, String, Logger, String, Throwable)]
    ///         with the given arguments, and has no effect when the future succeeds
    default <T> BiConsumer<T, Throwable> alertOnFailure(AdminAlertSeverity severity, String title, Logger logger, @Nullable String description) {
        return (_, e) -> {
            if (e != null) {
                raise(severity, title, logger, description, e);
            }
        };
    }

    /// Raises an alert as [#raise(AdminAlertSeverity, String, String)] does, with an empty description.
    default String raise(AdminAlertSeverity severity, String title) {
        return raise(severity, title, "");
    }

    /// Raises an alert as [#raise(AdminAlertData)] does, with empty [AdminAlertData#labels()].
    default String raise(AdminAlertSeverity severity, String title, String description) {
        return raise(AdminAlertData.builder()
                                   .setSeverity(severity)
                                   .setTitle(title)
                                   .setDescription(description)
                                   .build());
    }

    /// Adds an event with the [AdminAlertData#description()] of `data` to the active alert that has the [AdminAlertData#key()] of `data`, and first creates
    /// that alert when no alert with that key is active. Returns normally even when the alert cannot be recorded.
    ///
    /// @return the [AdminAlertData#key()] of `data`
    /// @implSpec An implementation that cannot record the alert must catch the failure, log it at WARN and return the key.
    String raise(AdminAlertData data);

    /// Resolves the active alert whose key is `key`, with `"system"` as its [AdminAlert#resolvedBy()] and `note` as its [AdminAlert#resolutionNote()]. Has no
    /// effect when no alert with that key is active. The condition that raised the alert must have ended.
    ///
    /// @implSpec An implementation that fails to resolve the alert must log the failure at WARN, and must not report it in any other way.
    void resolve(String key, String note);

    /// Resolves the alert whose [AdminAlert#id()] is `alertId`, with `resolvedBy` as its [AdminAlert#resolvedBy()] and `note` as its
    /// [AdminAlert#resolutionNote()].
    CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note);

    /// Deletes every resolved alert whose [AdminAlert#resolvedAt()] is more than `retention` before the current time.
    ///
    /// @return a future that completes with the number of alerts deleted
    CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention);

    /// Deletes every alert, active or resolved, whose [AdminAlert#labels()] map `labelName` to `labelValue`, and the events of each such alert.
    ///
    /// @param labelName  the name of the label; must not be blank
    /// @param labelValue the value that the label must equal; must not be blank, and may be personal data
    /// @return a future that completes with the number of alerts deleted
    /// @implSpec An implementation must keep `labelValue` out of the message of every failure, because the value may be personal data.
    CompletableFuture<Integer> deleteByLabel(String labelName, String labelValue);

    enum ResolveByIdOutcome {
        /// The alert was active, and [AdminAlertService#resolveById(String, String, Optional)] resolved it.
        RESOLVED,
        /// The alert was resolved before the call to [AdminAlertService#resolveById(String, String, Optional)], which changed nothing.
        ALREADY_RESOLVED,
        /// No alert has the id passed to [AdminAlertService#resolveById(String, String, Optional)].
        UNKNOWN
    }
}
