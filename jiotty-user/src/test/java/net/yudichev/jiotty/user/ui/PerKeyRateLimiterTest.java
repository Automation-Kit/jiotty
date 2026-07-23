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

    /// The bucket holds at most one second's worth, so an idle key cannot bank allowance and later burst beyond the rate.
    @Test
    void capsTheAllowanceAKeyCanBank() {
        PerKeyRateLimiter limiter = limiter(2.0);

        clock.advanceTimeAndTick(Duration.ofMinutes(5));

        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).isTrue();
        assertThat(limiter.tryAcquire("k")).as("five idle minutes buy no more than the one-second cap").isFalse();
    }

    /// One key exhausting its allowance must not spend another's.
    @Test
    void tracksKeysIndependently() {
        PerKeyRateLimiter limiter = limiter(2.0);

        limiter.tryAcquire("noisy");
        limiter.tryAcquire("noisy");
        assertThat(limiter.tryAcquire("noisy")).isFalse();

        assertThat(limiter.tryAcquire("other")).isTrue();
    }

    /// A rate below one per second admits at that spacing: the starting one-permit ceiling lets the first through, then each further whole permit takes more
    /// than a second to accrue.
    @Test
    void throttlesRatesBelowOnePerSecond() {
        PerKeyRateLimiter limiter = limiter(0.5);

        assertThat(limiter.tryAcquire("k")).as("the starting one-permit ceiling").isTrue();
        assertThat(limiter.tryAcquire("k")).isFalse();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("k")).as("half a permit accrued, not yet whole").isFalse();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("k")).as("a whole permit after two seconds at 0.5/s").isTrue();
    }

    /// A burst above the sustained rate lets a key fire that many at once, then meters further requests at the rate — an absorbed startup fan-out.
    @Test
    void admitsABurstUpToTheCeilingThenMetersAtTheRate() {
        var limiter = new PerKeyRateLimiter(clock, 2.0, 5, MANY_KEYS, IDLE_EVICTION);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k")).as("burst permit %s", i).isTrue();
        }
        assertThat(limiter.tryAcquire("k")).as("burst of 5 spent").isFalse();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("k")).as("one of the 2/s refill").isTrue();
        assertThat(limiter.tryAcquire("k")).as("two of the 2/s refill").isTrue();
        assertThat(limiter.tryAcquire("k")).as("but not a third in the same second").isFalse();
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
