package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TariffTimelineTest {
    private static final Instant FROM = Instant.parse("2026-11-21T00:00:00Z");

    @Test
    void anEmptyTimelineHasNeitherDynamicPricingNorSupplierManagedCharging() {
        var timeline = new TariffTimeline(ImmutableList.of());

        assertThat(timeline.hasDynamicPricing()).isFalse();
        assertThat(timeline.hasSupplierManagedCharging()).isFalse();
    }

    @Test
    void anyDynamicPeriodMakesTheTimelineDynamic() {
        var timeline = new TariffTimeline(ImmutableList.of(period(TariffPricing.PUBLISHED, false), period(TariffPricing.DYNAMIC, false)));

        assertThat(timeline.hasDynamicPricing()).isTrue();
        assertThat(timeline.hasSupplierManagedCharging()).isFalse();
    }

    @Test
    void anySupplierManagedPeriodMakesTheTimelineSupplierManaged() {
        var timeline = new TariffTimeline(ImmutableList.of(period(TariffPricing.PUBLISHED, true)));

        assertThat(timeline.hasDynamicPricing()).isFalse();
        assertThat(timeline.hasSupplierManagedCharging()).isTrue();
    }

    @Test
    void periodsCannotBeChanged() {
        List<TariffPeriod> periods = new ArrayList<>(List.of(period(TariffPricing.DYNAMIC, false)));
        var timeline = new TariffTimeline(periods);

        periods.clear();

        assertThat(timeline.periods()).hasSize(1).isInstanceOf(ImmutableList.class);
    }

    private static TariffPeriod period(TariffPricing pricing, boolean supplierManagedCharging) {
        return TariffPeriod.builder().setFrom(FROM).setPricing(pricing).setSupplierManagedCharging(supplierManagedCharging).build();
    }
}
