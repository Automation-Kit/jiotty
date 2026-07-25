package net.yudichev.jiotty.common.lang.throttling;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static java.time.temporal.ChronoUnit.NANOS;

/// A continuous token-bucket rate limiter: an allowance that refills at `permitsPerSecond` and holds at most `maxBurst` permits, so a caller may fire a burst
/// of up to `maxBurst` at once and then proceeds at the sustained rate. Decoupling the two absorbs a legitimate startup fan-out (many requests in a moment,
/// then quiet) while still capping sustained load at `permitsPerSecond`.
///
/// The caller supplies the time on each call and owns the clock, so behaviour is deterministic under test.
///
/// @implSpec Not thread-safe by design — a single allowance is inherently a shared counter, so this holds no lock of its own. Confine an instance to one
/// thread (e.g. a graph node's executor), or serialise [#tryAcquire] externally when several threads share one instance.
public final class RateLimiter {
    private final double permitsPerSecond;
    /// Ceiling on banked allowance — the burst size. Floored at one permit so a sub-one-per-second rate can still admit a request once enough time has passed;
    /// above that the rate alone decides how long a caller waits once its burst is spent.
    private final double maxPermits;
    private double availablePermits;
    private Instant lastRefill;

    public RateLimiter(double permitsPerSecond, double maxBurst, Instant now) {
        checkArgument(permitsPerSecond > 0, "permits per second must be positive: %s", permitsPerSecond);
        checkArgument(maxBurst > 0, "max burst must be positive: %s", maxBurst);
        this.permitsPerSecond = permitsPerSecond;
        maxPermits = Math.max(1.0, maxBurst);
        availablePermits = maxPermits;
        lastRefill = now;
    }

    /// Takes one permit as of `now`, returning whether one was available.
    public boolean tryAcquire(Instant now) {
        double elapsedSeconds = NANOS.between(lastRefill, now) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            availablePermits = Math.min(maxPermits, availablePermits + elapsedSeconds * permitsPerSecond);
            lastRefill = now;
        }
        if (availablePermits < 1.0) {
            return false;
        }
        availablePermits -= 1.0;
        return true;
    }
}
