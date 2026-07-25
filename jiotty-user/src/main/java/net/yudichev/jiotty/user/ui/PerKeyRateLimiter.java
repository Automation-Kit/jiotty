package net.yudichev.jiotty.user.ui;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.yudichev.jiotty.common.lang.throttling.RateLimiter;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;

import java.time.Duration;
import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// A concurrent rate limiter keyed by an arbitrary string — a source address, a user id. Each key gets its own [RateLimiter] allowance (a burst of `maxBurst`
/// then a sustained `permitsPerSecond`), so a key may fan out a burst and then proceeds at the rate. The key map is bounded and evicts idle keys, so tracking
/// cannot itself become a memory-exhaustion vector: eviction only costs an abusive key its accumulated debt, and a legitimate key its (full) allowance. Time
/// comes from a [CurrentDateTimeProvider] so tests drive it deterministically.
final class PerKeyRateLimiter {
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final double permitsPerSecond;
    private final double maxBurst;
    private final Cache<String, RateLimiter> limitersByKey;

    PerKeyRateLimiter(CurrentDateTimeProvider currentDateTimeProvider, double permitsPerSecond, double maxBurst, int maxKeys, Duration idleEviction) {
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        checkArgument(permitsPerSecond > 0, "permits per second must be positive: %s", permitsPerSecond);
        checkArgument(maxBurst > 0, "max burst must be positive: %s", maxBurst);
        checkArgument(maxKeys > 0, "max keys must be positive: %s", maxKeys);
        this.permitsPerSecond = permitsPerSecond;
        this.maxBurst = maxBurst;
        limitersByKey = CacheBuilder.newBuilder()
                                    .maximumSize(maxKeys)
                                    .expireAfterAccess(idleEviction)
                                    .build();
    }

    /// Takes one permit for `key`, returning whether the key was within its allowance.
    boolean tryAcquire(String key) {
        Instant now = currentDateTimeProvider.currentInstant();
        // computeIfAbsent single lookup atomically shares one limiter per key; the RateLimiter itself is single-threaded, so serialise the take on its monitor.
        RateLimiter limiter = limitersByKey.asMap().computeIfAbsent(key, _ -> new RateLimiter(permitsPerSecond, maxBurst, now));
        synchronized (limiter) {
            return limiter.tryAcquire(now);
        }
    }
}
