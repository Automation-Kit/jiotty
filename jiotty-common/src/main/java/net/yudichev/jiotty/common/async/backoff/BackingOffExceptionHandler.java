package net.yudichev.jiotty.common.async.backoff;

import java.util.Optional;

public interface BackingOffExceptionHandler {
    /// Decides whether the given exception is retryable and, if so, computes the backoff delay the caller must wait before the next attempt. Returns
    /// immediately; applying the delay is the caller's responsibility.
    ///
    /// @return the backoff delay to apply before retrying, in milliseconds, or empty if the exception is not retryable
    /// @throws IllegalStateException if the operation has been retried for longer than the configured maximum elapsed time (give up)
    Optional<Long> handle(String operationName, Throwable exception);

    void reset();
}
