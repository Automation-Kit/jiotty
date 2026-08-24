package net.yudichev.jiotty.common.misc;

import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static net.yudichev.jiotty.common.misc.SharedUpstreamOutage.indicatesSharedOutage;

/// Retries a shared connector's calls and reports the retried outcome to the connector's [UpstreamHealthHandler].
public final class UpstreamHealthReporting {
    private UpstreamHealthReporting() {
    }

    /// Runs `operation` with backoff retries on `retryableOperationExecutor` and reports the retried outcome to `healthHandler`: a success reports recovery,
    /// a [shared-outage][SharedUpstreamOutage] failure reports an outage under `failureMessage`, and any other failure (a 4xx, a caller bug) is the caller's
    /// own and reports neither. Returns the operation's own future, carrying the operation's outcome.
    public static <T> CompletableFuture<T> reportingHealth(RetryableOperationExecutor retryableOperationExecutor,
                                                           UpstreamHealthHandler healthHandler,
                                                           String operationName,
                                                           String failureMessage,
                                                           Supplier<? extends CompletableFuture<T>> operation) {
        CompletableFuture<T> future = retryableOperationExecutor.withBackOffAndRetry(operationName, operation);
        future.whenComplete((_, throwableOrNull) -> {
            if (throwableOrNull == null) {
                healthHandler.onSuccess();
            } else if (indicatesSharedOutage(throwableOrNull)) {
                healthHandler.onFailure(failureMessage, throwableOrNull);
            }
        });
        return future;
    }
}
