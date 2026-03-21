package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.backoff.BackOff;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public interface AsyncOperationFailureHandler {
    /// @return backoff delay applied, in milliseconds, or 0, if no backoff delay was applied
    Optional<Long> handle(String operationName, Throwable exception);

    void reset();

    static AsyncOperationFailureHandler forBackoff(BackOff backOff, Logger logger) {
        return forBackoff(backOff, logger, () -> {});
    }

    static AsyncOperationFailureHandler forBackoff(BackOff backOff, Logger logger, Runnable onPermanentFailure) {
        return new AsyncOperationFailureHandler() {
            @Override
            public Optional<Long> handle(String operationName, Throwable e) {
                var nextBackoffMillis = backOff.nextBackOffMillis();
                if (nextBackoffMillis == BackOff.STOP) {
                    logger.error("Failed '{}' permanently, no more retries", operationName, e);
                    onPermanentFailure.run();
                    return Optional.empty();
                } else {
                    logger.warn("Failed '{}', will retry in {}ms", operationName, nextBackoffMillis, e);
                    return Optional.of(nextBackoffMillis);
                }
            }

            @Override
            public void reset() {
                backOff.reset();
            }
        };
    }
}
