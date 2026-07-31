package net.yudichev.jiotty.adminalerts;

import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

/// A single admin alert for one shared condition that many subjects observe independently — e.g. an external API outage seen by every user's poller. One
/// instance represents one condition (fixed title and severity, which also fix the [alert key][AdminAlertData#key()]); subjects report their observations
/// into it, and the instance aggregates them so the operator sees one alert bundle regardless of how many subjects are affected.
///
/// The bundle carries one event per distinct cause, keyed on the cause's [HumanReadableExceptionMessage#humanReadableMessage(Throwable)] footprint: a
/// thousand subjects hitting the same 502 add one event carrying that 502, and a subject hitting a different failure adds a second event carrying that one.
/// Every report is logged, so the per-subject occurrences remain in the log.
///
/// Safe to call from any thread: every report and clear is handed to the executor, which applies the state change and the raise or resolve it produces as one
/// unit. Subject ids are held in this instance's memory.
public final class SharedConditionAdminAlerter {
    private final AdminAlertService alertService;
    private final AdminAlertSeverity severity;
    private final String title;
    /// Serialises every state transition together with the alert-service call it produces, so a resolve can never overtake a raise made after it.
    private final Executor executor;
    private final Set<String> failingSubjectIds = new HashSet<>();
    /// Cause footprints already carried by an event on the active bundle, so a footprint every subject shares is raised once. Emptied on resolution, since the
    /// next raise opens a fresh bundle.
    private final Set<String> reportedFootprints = new HashSet<>();
    /// Key of the currently active alert; `null` exactly when [#failingSubjectIds] is empty.
    private @Nullable String alertKey;

    /// @param executor single-threaded executor confining this instance's state; several instances may share one.
    public SharedConditionAdminAlerter(AdminAlertService alertService, AdminAlertSeverity severity, String title, Executor executor) {
        this.alertService = checkNotNull(alertService, "alertService");
        this.severity = checkNotNull(severity, "severity");
        this.title = checkNotNull(title, "title");
        this.executor = checkNotNull(executor, "executor");
    }

    /// Records `subjectId` as observing the condition and logs the failure. Adds an event carrying `cause` when its footprint is new to the active bundle.
    public void reportFailure(String subjectId, Logger logger, Throwable cause) {
        checkNotNull(subjectId, "subjectId");
        executor.execute(() -> {
            String footprint = humanReadableMessage(cause);
            failingSubjectIds.add(subjectId);
            if (reportedFootprints.add(footprint)) {
                alertKey = alertService.raise(severity, title, logger, cause);
            } else {
                logger.info("{}", title, cause);
            }
        });
    }

    /// Records `subjectId` as no longer observing the condition — because it recovered or because it is going away — and resolves the alert if it was the
    /// last failing subject. A no-op for a subject that was not failing, so it is safe to call on every success.
    public void clear(String subjectId) {
        checkNotNull(subjectId, "subjectId");
        executor.execute(() -> {
            if (!failingSubjectIds.remove(subjectId) || !failingSubjectIds.isEmpty()) {
                return;
            }
            assert alertKey != null : "an alert is active whenever a subject is failing";
            String keyToResolve = alertKey;
            alertKey = null;
            reportedFootprints.clear();
            alertService.resolve(keyToResolve, "all reporting subjects recovered");
        });
    }
}
