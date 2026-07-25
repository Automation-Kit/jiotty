package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerKeyRateLimiterTest {
    private static final int MANY_KEYS = 10_000;
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(10);

    private ProgrammableClock clock;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-07-20T12:00:00Z"));
    }

    /// A key spends its allowance, is refused, and is served again once the bucket has refilled.
    @Test
    void limitsEachKeyToItsRate() {
        PerKeyRateLimiter limiter = limiter(2.0);

        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).as("allowance spent").isFalse();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("k")).as("refilled").isTrue();
    }

    /// One key exhausting its allowance must not spend another's — the per-key isolation this facade adds over a single shared allowance.
    @Test
    void tracksKeysIndependently() {
        PerKeyRateLimiter limiter = limiter(2.0);

        limiter.tryAcquire("noisy");
        limiter.tryAcquire("noisy");
        assertThat(limiter.tryAcquire("noisy")).isFalse();

        assertThat(limiter.tryAcquire("other")).isTrue();
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new PerKeyRateLimiter(clock, 0.0, 5.0, MANY_KEYS, IDLE_EVICTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits per second");
    }

    @Test
    void rejectsNonPositiveBurst() {
        assertThatThrownBy(() -> new PerKeyRateLimiter(clock, 1.0, 0.0, MANY_KEYS, IDLE_EVICTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max burst");
    }

    @Test
    void rejectsNonPositiveMaxKeys() {
        assertThatThrownBy(() -> new PerKeyRateLimiter(clock, 1.0, 1.0, 0, IDLE_EVICTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max keys");
    }

    private PerKeyRateLimiter limiter(double permitsPerSecond) {
        // Burst equal to the rate: the tests above that share this helper assert the burst-equals-rate behaviour the per-source guard uses.
        return new PerKeyRateLimiter(clock, permitsPerSecond, permitsPerSecond, MANY_KEYS, IDLE_EVICTION);
    }
}
