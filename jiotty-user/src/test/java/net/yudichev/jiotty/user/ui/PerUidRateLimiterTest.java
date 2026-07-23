package net.yudichev.jiotty.user.ui;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PerUidRateLimiterTest {
    private ProgrammableClock clock;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-07-20T12:00:00Z"));
        meterRegistry = new SimpleMeterRegistry();
    }

    /// A user spends its allowance, is refused, and is admitted again once the allowance has refilled.
    @Test
    void limitsEachUserToItsRate() {
        var limiter = new PerUidRateLimiter(clock, 2.0, 2.0, meterRegistry);

        assertThat(limiter.tryAdmit("user-1")).isTrue();
        assertThat(limiter.tryAdmit("user-1")).isTrue();
        assertThat(limiter.tryAdmit("user-1")).as("allowance spent").isFalse();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        assertThat(limiter.tryAdmit("user-1")).as("refilled").isTrue();
    }

    /// One user exhausting its allowance must not spend another's.
    @Test
    void tracksUsersIndependently() {
        var limiter = new PerUidRateLimiter(clock, 2.0, 2.0, meterRegistry);

        limiter.tryAdmit("noisy");
        limiter.tryAdmit("noisy");
        assertThat(limiter.tryAdmit("noisy")).isFalse();

        assertThat(limiter.tryAdmit("other")).isTrue();
    }

    /// Each rejection is counted under the per-user guard, which is what tells a rate-limited user apart from other shed traffic on the dashboard.
    @Test
    void countsRejectionsUnderThePerUidGuard() {
        var limiter = new PerUidRateLimiter(clock, 1.0, 1.0, meterRegistry);

        assertThat(limiter.tryAdmit("user-1")).isTrue();
        assertThat(limiter.tryAdmit("user-1")).isFalse();
        assertThat(limiter.tryAdmit("user-1")).isFalse();

        assertThat(meterRegistry.get("guard_rejected_total").tag("guard", "per_uid").tag("outcome", "rate_limited").counter().count())
                .isEqualTo(2.0);
    }
}
