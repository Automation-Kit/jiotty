package net.yudichev.jiotty.common.lang.throttling;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {
    private static final Instant T0 = Instant.parse("2026-07-20T12:00:00Z");

    /// The allowance is spent, refused, and available again once enough time has passed at the rate.
    @Test
    void limitsToItsRate() {
        var limiter = new RateLimiter(2.0, 2.0, T0);

        assertThat(limiter.tryAcquire(T0)).isTrue();
        assertThat(limiter.tryAcquire(T0)).isTrue();
        assertThat(limiter.tryAcquire(T0)).as("allowance spent").isFalse();

        assertThat(limiter.tryAcquire(T0.plusSeconds(1))).as("refilled").isTrue();
    }

    /// The allowance holds at most `maxBurst`, so idle time cannot bank beyond it.
    @Test
    void capsBankedAllowanceAtTheBurst() {
        var limiter = new RateLimiter(2.0, 2.0, T0);

        Instant later = T0.plusSeconds(300);
        assertThat(limiter.tryAcquire(later)).isTrue();
        assertThat(limiter.tryAcquire(later)).isTrue();
        assertThat(limiter.tryAcquire(later)).as("five idle minutes buy no more than the two-permit burst").isFalse();
    }

    /// A burst above the sustained rate lets the allowance fire that many at once, then meters further requests at the rate.
    @Test
    void admitsABurstAboveTheRateThenMetersAtTheRate() {
        var limiter = new RateLimiter(2.0, 5.0, T0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(T0)).as("burst permit %s", i).isTrue();
        }
        assertThat(limiter.tryAcquire(T0)).as("burst of 5 spent").isFalse();

        Instant oneLater = T0.plusSeconds(1);
        assertThat(limiter.tryAcquire(oneLater)).as("one of the 2/s refill").isTrue();
        assertThat(limiter.tryAcquire(oneLater)).as("two of the 2/s refill").isTrue();
        assertThat(limiter.tryAcquire(oneLater)).as("but not a third in the same second").isFalse();
    }

    /// A rate below one per second admits at that spacing: the starting one-permit ceiling lets the first through, then each further whole permit takes more
    /// than a second to accrue.
    @Test
    void throttlesRatesBelowOnePerSecond() {
        var limiter = new RateLimiter(0.5, 0.5, T0);

        assertThat(limiter.tryAcquire(T0)).as("the starting one-permit ceiling").isTrue();
        assertThat(limiter.tryAcquire(T0)).isFalse();

        assertThat(limiter.tryAcquire(T0.plusSeconds(1))).as("half a permit accrued, not yet whole").isFalse();
        assertThat(limiter.tryAcquire(T0.plusSeconds(2))).as("a whole permit after two seconds at 0.5/s").isTrue();
    }

    @Test
    void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> new RateLimiter(0.0, 5.0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("permits per second");
    }

    @Test
    void rejectsNonPositiveBurst() {
        assertThatThrownBy(() -> new RateLimiter(1.0, 0.0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max burst");
    }
}
