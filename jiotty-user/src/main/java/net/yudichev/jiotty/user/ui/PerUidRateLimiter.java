package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Rate limit on how fast one authenticated user may drive the API, keyed by the verified user id. It complements the pre-auth per-source guard: that one
/// bounds unauthenticated callers by address so a flood cannot exhaust token verification; this one bounds an authenticated user by identity, so a single
/// account cannot hammer the API from many addresses (or from one behind NAT). The key is the id the token verifier vouched for, never a client-supplied
/// value.
final class PerUidRateLimiter {
    private static final String REJECTED_COUNTER = "guard_rejected_total";
    /// Caps the per-user bucket map so tracking cannot itself exhaust memory. A user is at most one entry; eviction only costs an idle user its (full) bucket.
    private static final int MAX_TRACKED_USERS = 10_000;
    private static final Duration USER_IDLE_EVICTION = Duration.ofMinutes(15);

    private final PerKeyRateLimiter rateLimiter;
    private final Counter rateLimitedCounter;

    @Inject
    PerUidRateLimiter(CurrentDateTimeProvider currentDateTimeProvider,
                      @RequestsPerSecond double permitsPerSecond,
                      @MaxBurst double maxBurst,
                      MeterRegistry meterRegistry) {
        rateLimiter = new PerKeyRateLimiter(currentDateTimeProvider, permitsPerSecond, maxBurst, MAX_TRACKED_USERS, USER_IDLE_EVICTION);
        rateLimitedCounter = meterRegistry.counter(REJECTED_COUNTER, "guard", "per_uid", "outcome", "rate_limited");
    }

    /// Whether the user identified by `userId` is within its API allowance, counting a rejection when it is not.
    boolean tryAdmit(String userId) {
        if (rateLimiter.tryAcquire(checkNotNull(userId))) {
            return true;
        }
        rateLimitedCounter.increment();
        return false;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface RequestsPerSecond {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface MaxBurst {
    }
}
