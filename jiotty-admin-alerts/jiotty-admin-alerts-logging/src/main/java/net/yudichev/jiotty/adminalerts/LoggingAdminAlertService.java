package net.yudichev.jiotty.adminalerts;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.CompletableFuture.completedFuture;

/// Log-only [AdminAlertService]: every raised alert is logged at the severity-matching level and tracked in memory so a matching [#resolve(String, String)]
/// can log a symmetrical resolution line. Suitable for deployments that want operator-visible failure signalling without a persistent store or alerting UI —
/// there is no history, no bundling, and no operator-driven resolution.
public final class LoggingAdminAlertService implements AdminAlertService {
    private static final Logger logger = LogManager.getLogger(LoggingAdminAlertService.class);

    private final ConcurrentHashMap<String, AdminAlertSeverity> severityByKey = new ConcurrentHashMap<>();

    @Override
    public String raise(AdminAlertData data) {
        String key = data.key();
        try {
            severityByKey.put(key, data.severity());
            logger.log(levelOf(data.severity()), "ALERT RAISED {}: {}", data.title(), data.description());
        } catch (RuntimeException e) {
            // [AdminAlertService#raise] is total by contract; see its @implSpec.
            logger.warn("Failed to raise alert with key {}", key, e);
        }
        return key;
    }

    @Override
    public CompletableFuture<Optional<String>> resolve(String key, String note) {
        AdminAlertSeverity severity = severityByKey.remove(key);
        if (severity == null) {
            return completedFuture(Optional.empty());
        }
        logger.log(levelOf(severity), "ALERT RESOLVED {}: {}", key, note);
        return completedFuture(Optional.of(key));
    }

    @Override
    public CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note) {
        return completedFuture(ResolveByIdOutcome.UNKNOWN);
    }

    @Override
    public CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention) {
        return completedFuture(0);
    }

    private static Level levelOf(AdminAlertSeverity severity) {
        return switch (severity) {
            case WARNING -> Level.WARN;
            case ERROR -> Level.ERROR;
        };
    }
}
