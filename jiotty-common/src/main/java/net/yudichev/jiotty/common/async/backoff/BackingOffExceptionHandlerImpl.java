package net.yudichev.jiotty.common.async.backoff;

import com.google.common.base.MoreObjects;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.backoff.BackOff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getCausalChain;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

final class BackingOffExceptionHandlerImpl implements BackingOffExceptionHandler {
    private static final Logger logger = LogManager.getLogger(BackingOffExceptionHandlerImpl.class);
    private final BackOff backOff;
    private final Predicate<? super Throwable> retryableExceptionPredicate;

    @Inject
    BackingOffExceptionHandlerImpl(@Dependency BackOff backOff, @Dependency Predicate<? super Throwable> retryableExceptionPredicate) {
        this.backOff = checkNotNull(backOff);
        this.retryableExceptionPredicate = checkNotNull(retryableExceptionPredicate);
    }

    @Override
    public Optional<Long> handle(String operationName, Throwable exception) {
        return getCausalChain(exception).stream()
                                        .filter(retryableExceptionPredicate)
                                        .findFirst()
                                        .map(throwable -> {
                                            long backOffMs = backOff.nextBackOffMillis();
                                            logger.debug("Operation '{}': backoff: {}", operationName, backOff);
                                            if (backOffMs == BackOff.STOP) {
                                                //noinspection StringConcatenationMissingWhitespace
                                                throw new IllegalStateException(
                                                        "Operation " + operationName + " is being retried for too long (" + backOff.getMaxElapsedTimeMillis()
                                                        + "ms) - giving up, last error included", throwable);
                                            }
                                            logger.debug("Retryable exception performing operation '{}', backing off for {}ms",
                                                         operationName, backOffMs, throwable);
                                            return Optional.of(backOffMs);
                                        })
                                        .orElse(Optional.empty());
    }

    @Override
    public void reset() {
        asUnchecked(backOff::reset);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("backOff", backOff)
                          .toString();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
