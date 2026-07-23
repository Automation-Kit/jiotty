package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Admission control for requests that have presented a bearer token but have not been authenticated yet. Token verification is the most expensive thing the
/// server does for a caller who has proven nothing, so this bounds two things ahead of it: how fast one source may ask, and how many verifications may be in
/// flight at once. Both rejections are cheap and terminal — the request never reaches the authenticator.
///
/// Every [#tryAdmit] returning [Outcome#ADMITTED] takes an in-flight permit that the caller must return via [#releaseInFlight], on every completion path
/// including failure and timeout. A leaked permit is never recovered and permanently shrinks the pool.
final class PreAuthAdmissionControl {
    private static final String REJECTED_COUNTER = "guard_rejected_total";
    private static final String INFLIGHT_GAUGE = "preauth_verify_inflight";
    private static final String INFLIGHT_LIMIT_GAUGE = "preauth_verify_inflight_limit";
    /// Caps the per-source bucket map, which would otherwise grow one entry per source address seen — an exhaustion vector in its own right. Eviction only
    /// costs an abusive source its accumulated debt, and a legitimate source its (full) bucket.
    private static final int MAX_TRACKED_SOURCES = 10_000;
    private static final Duration SOURCE_IDLE_EVICTION = Duration.ofMinutes(10);

    private final boolean trustProxyHeaders;
    private final Semaphore inFlightVerifications;
    private final PerKeyRateLimiter perSourceRateLimiter;
    private final Counter rateLimitedCounter;
    private final Counter verifySaturatedCounter;

    @Inject
    PreAuthAdmissionControl(CurrentDateTimeProvider currentDateTimeProvider,
                            @RequestsPerSecond double permitsPerSecond,
                            @MaxInFlightVerifications int maxInFlightVerifications,
                            @TrustProxyHeaders boolean trustProxyHeaders,
                            MeterRegistry meterRegistry) {
        checkArgument(maxInFlightVerifications > 0, "max in-flight verifications must be positive: %s", maxInFlightVerifications);
        this.trustProxyHeaders = trustProxyHeaders;
        inFlightVerifications = new Semaphore(maxInFlightVerifications);
        // A source's burst equals its sustained rate here: an unauthenticated caller has no legitimate startup fan-out to absorb, unlike an authenticated user.
        perSourceRateLimiter = new PerKeyRateLimiter(currentDateTimeProvider, permitsPerSecond, permitsPerSecond, MAX_TRACKED_SOURCES, SOURCE_IDLE_EVICTION);
        rateLimitedCounter = meterRegistry.counter(REJECTED_COUNTER, "guard", "pre_auth", "outcome", "rate_limited");
        verifySaturatedCounter = meterRegistry.counter(REJECTED_COUNTER, "guard", "pre_auth", "outcome", "verify_saturated");
        // Occupancy alongside its limit, so saturation reads as a ratio at any level. Both take the supplier form: the value-and-function form holds its
        // subject weakly, and a boxed limit that nothing else retains is collectable — the gauge then reports NaN and the ratio alert stops evaluating.
        Gauge.builder(INFLIGHT_GAUGE, () -> maxInFlightVerifications - inFlightVerifications.availablePermits()).register(meterRegistry);
        Gauge.builder(INFLIGHT_LIMIT_GAUGE, () -> maxInFlightVerifications).register(meterRegistry);
    }

    /// Decides whether `request` may proceed to token verification, taking an in-flight permit when it may.
    Outcome tryAdmit(HttpServletRequest request) {
        if (!perSourceRateLimiter.tryAcquire(sourceOf(request))) {
            rateLimitedCounter.increment();
            return Outcome.RATE_LIMITED;
        }
        if (!inFlightVerifications.tryAcquire()) {
            verifySaturatedCounter.increment();
            return Outcome.VERIFY_SATURATED;
        }
        return Outcome.ADMITTED;
    }

    void releaseInFlight() {
        inFlightVerifications.release();
    }

    /// The address this request is rate-limited against. `X-Forwarded-For` is honoured only where the deployment puts a trusted proxy in front, because a
    /// directly-reachable server lets any caller set that header and so choose its own bucket. Where it is honoured, the **last** entry is the one the proxy
    /// itself appended; earlier entries are client-supplied and forgeable.
    private String sourceOf(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null) {
                int lastEntry = forwardedFor.lastIndexOf(',');
                String candidate = (lastEntry < 0 ? forwardedFor : forwardedFor.substring(lastEntry + 1)).trim();
                if (!candidate.isEmpty()) {
                    return candidate;
                }
            }
        }
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    enum Outcome {
        /// The request may proceed to token verification, and holds an in-flight permit that the caller must return via [#releaseInFlight].
        ADMITTED,
        /// This request's source has spent its allowance. The allowance refills continuously, so the same source is admitted again once enough time passes.
        RATE_LIMITED,
        /// Every verification slot is occupied. This says nothing about the source's own rate — a source that has spent none of its allowance still gets
        /// this while the pool is full.
        VERIFY_SATURATED
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface RequestsPerSecond {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface MaxInFlightVerifications {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface TrustProxyHeaders {
    }
}
