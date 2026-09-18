package net.yudichev.jiotty.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.metrics.GuardMetrics.OUTCOME_QUOTA_EXCEEDED;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.OUTCOME_RATE_LIMITED;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.OUTCOME_VERIFY_SATURATED;
import static net.yudichev.jiotty.common.metrics.GuardMetrics.REJECTED_COUNTER;
import static org.assertj.core.api.Assertions.assertThat;

/// These are wire values, not code: the dashboards and the alert rules in `infra/grafana` name them as literal strings, so a rename here silently detaches
/// every panel and rule that joins on them rather than failing anything.
final class GuardMetricsTest {
    @Test
    void namesTheSeriesTheDashboardsAndAlertRulesJoinOn() {
        assertThat(REJECTED_COUNTER).isEqualTo("guard_rejected_total");
        assertThat(OUTCOME_QUOTA_EXCEEDED).isEqualTo("quota_exceeded");
        assertThat(OUTCOME_RATE_LIMITED).isEqualTo("rate_limited");
        assertThat(OUTCOME_VERIFY_SATURATED).isEqualTo("verify_saturated");
    }

    /// Every limiter labels the same series, so two guards using this vocabulary have to produce two distinct meters rather than one shared total.
    @Test
    void oneSeriesPerGuardAndOutcomePair() {
        var meterRegistry = new SimpleMeterRegistry();

        meterRegistry.counter(REJECTED_COUNTER, "guard", "ai_help", "outcome", OUTCOME_QUOTA_EXCEEDED).increment();
        meterRegistry.counter(REJECTED_COUNTER, "guard", "support_ticket", "outcome", OUTCOME_QUOTA_EXCEEDED).increment(2);

        assertThat(meterRegistry.counter(REJECTED_COUNTER, "guard", "ai_help", "outcome", OUTCOME_QUOTA_EXCEEDED).count()).isEqualTo(1);
        assertThat(meterRegistry.counter(REJECTED_COUNTER, "guard", "support_ticket", "outcome", OUTCOME_QUOTA_EXCEEDED).count()).isEqualTo(2);
    }
}
