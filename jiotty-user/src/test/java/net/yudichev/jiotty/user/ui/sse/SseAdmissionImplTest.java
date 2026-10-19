package net.yudichev.jiotty.user.ui.sse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.yudichev.jiotty.common.metrics.GuardMetrics;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SseAdmissionImplTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void admitsUpToItsLimitAndCountsEveryStreamItRefuses() {
        var admission = new SseAdmissionImpl(2, meterRegistry);

        assertThat(admission.tryAdmit()).isTrue();
        assertThat(admission.tryAdmit()).isTrue();
        assertThat(admission.tryAdmit()).isFalse();

        assertThat(rejections(GuardMetrics.OUTCOME_AT_CAPACITY)).isEqualTo(1);
        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE).gauge().value()).isEqualTo(2);
        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_LIMIT_GAUGE).gauge().value()).isEqualTo(2);
    }

    @Test
    void aReleasedPlaceIsAdmittedAgain() {
        var admission = new SseAdmissionImpl(1, meterRegistry);
        assertThat(admission.tryAdmit()).isTrue();

        admission.release();

        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE).gauge().value()).isZero();
        assertThat(admission.tryAdmit()).isTrue();
    }

    @Test
    void countsChannelRefusalsAndSlowReaderDropsOnTheirOwnSeries() {
        var admission = new SseAdmissionImpl(1, meterRegistry);

        admission.recordChannelFull();
        admission.recordSlowReaderDropped();
        admission.recordSlowReaderDropped();

        assertThat(rejections(GuardMetrics.OUTCOME_USER_AT_CAPACITY)).isEqualTo(1);
        assertThat(meterRegistry.counter(SseAdmissionImpl.DROPPED_COUNTER).count()).isEqualTo(2);
    }

    @Test
    void theProductionLimitIsTheOnePublished() {
        new SseAdmissionImpl(meterRegistry);

        assertThat(meterRegistry.get(SseAdmissionImpl.ACTIVE_CLIENTS_LIMIT_GAUGE).gauge().value()).isEqualTo(SseAdmissionImpl.MAX_STREAMS);
    }

    @Test
    void refusesANonPositiveLimit() {
        assertThatThrownBy(() -> new SseAdmissionImpl(0, meterRegistry)).isInstanceOf(IllegalArgumentException.class);
    }

    /// The meters' names are what the dashboards and the alert rule join on, so a rename here detaches them without failing anything else.
    @Test
    void namesTheSeriesTheDashboardsAndAlertRulesJoinOn() {
        assertThat(SseAdmissionImpl.ACTIVE_CLIENTS_GAUGE).isEqualTo("sse_active_clients");
        assertThat(SseAdmissionImpl.ACTIVE_CLIENTS_LIMIT_GAUGE).isEqualTo("sse_active_clients_limit");
        assertThat(SseAdmissionImpl.DROPPED_COUNTER).isEqualTo("sse_client_dropped_total");
        assertThat(SseAdmissionImpl.GUARD).isEqualTo("sse");
    }

    private double rejections(String outcome) {
        return meterRegistry.counter(GuardMetrics.REJECTED_COUNTER, GuardMetrics.TAG_GUARD, SseAdmissionImpl.GUARD, GuardMetrics.TAG_OUTCOME, outcome).count();
    }
}
