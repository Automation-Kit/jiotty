package net.yudichev.jiotty.user.ui;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;

import java.time.Duration;
import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.temporal.ChronoUnit.NANOS;

/// A continuous token-bucket rate limiter keyed by an arbitrary string — a source address, a user id. Each key gets its own allowance that refills at
/// `permitsPerSecond` and holds at most `maxBurst` permits, so a key may fire a burst of up to `maxBurst` at once and then proceeds at the sustained rate.
/// Decoupling the two absorbs a legitimate startup fan-out (many requests in a moment, then quiet) while still capping sustained abuse at `permitsPerSecond`.
/// The key map is bounded and evicts idle keys, so tracking cannot itself become a memory-exhaustion vector: eviction only costs an abusive key its
/// accumulated debt, and a legitimate key its (full) bucket. Time comes from a [CurrentDateTimeProvider] so tests drive it deterministically.
final class PerKeyRateLimiter {
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final double permitsPerSecond;
    private final double maxBurst;
    private final Cache<String, TokenBucket> bucketsByKey;

    PerKeyRateLimiter(CurrentDateTimeProvider currentDateTimeProvider, double permitsPerSecond, double maxBurst, int maxKeys, Duration idleEviction) {
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        checkArgument(permitsPerSecond > 0, "permits per second must be positive: %s", permitsPerSecond);
        checkArgument(maxBurst > 0, "max burst must be positive: %s", maxBurst);
        checkArgument(maxKeys > 0, "max keys must be positive: %s", maxKeys);
        this.permitsPerSecond = permitsPerSecond;
        this.maxBurst = maxBurst;
        bucketsByKey = CacheBuilder.newBuilder()
                                   .maximumSize(maxKeys)
                                   .expireAfterAccess(idleEviction)
                                   .build();
    }

    /// Takes one permit for `key`, returning whether the key was within its allowance.
    boolean tryAcquire(String key) {
        Instant now = currentDateTimeProvider.currentInstant();
        // computeIfAbsent-style single lookup: the bucket is mutated under its own monitor, so concurrent requests for one key share it safely.
        TokenBucket bucket = bucketsByKey.asMap().computeIfAbsent(key, _ -> new TokenBucket(permitsPerSecond, maxBurst, now));
        return bucket.tryTake(now);
    }

    /// A per-key allowance that refills continuously at `permitsPerSecond` and holds at most `maxBurst` permits, so a key may burst up to that ceiling and
    /// then proceeds at the sustained rate. Time comes from the caller so tests drive it deterministically.
    private static final class TokenBucket {
        private final double permitsPerSecond;
        /// Ceiling on banked allowance — the burst size. Floored at one permit so a sub-one-per-second rate can still admit a request once enough time has
        /// passed; above that the rate alone decides how long a key waits once its burst is spent.
        private final double maxPermits;
        private double availablePermits;
        private Instant lastRefill;

        TokenBucket(double permitsPerSecond, double maxBurst, Instant now) {
            this.permitsPerSecond = permitsPerSecond;
            maxPermits = Math.max(1.0, maxBurst);
            availablePermits = maxPermits;
            lastRefill = now;
        }

        synchronized boolean tryTake(Instant now) {
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
}
