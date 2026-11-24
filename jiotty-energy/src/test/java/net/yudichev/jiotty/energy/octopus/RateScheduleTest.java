package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.energy.octopus.RateSchedule.RateSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.energy.octopus.OctopusStreams.DIRECT_DEBIT;
import static net.yudichev.jiotty.energy.octopus.TestRates.createRate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateScheduleTest {
    private static final String PRODUCT = "IOG-SMB-VAR-26-11-04";
    private static final String TARIFF = "E-1R-IOG-SMB-VAR-26-11-04-A";

    @Mock
    private OctopusRegionService regionService;

    @Test
    void standardUnitRates_keepTheDirectDebitRow_andEndAnOpenEndedOneAtTheRangeEnd() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");
        Instant to = Instant.parse("2026-12-06T00:00:00Z");
        when(regionService.getStandardUnitRates(PRODUCT, TARIFF, from, to))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554, DIRECT_DEBIT),
                                                    createRate("2026-09-30T23:00:00Z", 28.0484, "NON_DIRECT_DEBIT"))));

        assertThat(fetch(RateSchedule.STANDARD_UNIT_RATES, from, to))
                .containsExactly(new RateSegment(Instant.parse("2026-09-30T23:00:00Z"), to, 26.554));
    }

    @Test
    void standardUnitRates_noRows_makeNoSegments() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T00:00:00Z");
        when(regionService.getStandardUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of()));

        assertThat(fetch(RateSchedule.STANDARD_UNIT_RATES, from, to)).isEmpty();
    }

    @Test
    void standardUnitRates_aRowThatEndsWhereItStarts_makesNoSegment() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T00:00:00Z");
        when(regionService.getStandardUnitRates(PRODUCT, TARIFF, from, to))
                .thenReturn(completedFuture(List.of(createRate("2026-11-21T00:00:00Z", "2026-11-21T00:00:00Z", 26.554))));

        assertThat(fetch(RateSchedule.STANDARD_UNIT_RATES, from, to)).isEmpty();
    }

    @Test
    void dayAndNightRates_layTheNightRateOnTheOffPeakWindow() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T00:00:00Z");
        when(regionService.getDayUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-11-05T00:00:00Z", 34.12))));
        when(regionService.getNightUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-11-05T00:00:00Z", 9.9))));

        assertThat(fetch(RateSchedule.DAY_AND_NIGHT_RATES, from, to)).containsExactly(
                new RateSegment(from, Instant.parse("2026-11-21T05:30:00Z"), 9.9),
                new RateSegment(Instant.parse("2026-11-21T05:30:00Z"), Instant.parse("2026-11-21T23:30:00Z"), 34.12),
                new RateSegment(Instant.parse("2026-11-21T23:30:00Z"), to, 9.9));
    }

    @Test
    void dayAndNightRates_followUkClockTime_inSummer() {
        Instant from = Instant.parse("2026-07-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T06:00:00Z");
        when(regionService.getDayUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-01-01T00:00:00Z", 34.12))));
        when(regionService.getNightUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-01-01T00:00:00Z", 9.9))));

        assertThat(fetch(RateSchedule.DAY_AND_NIGHT_RATES, from, to)).containsExactly(new RateSegment(from, Instant.parse("2026-07-01T04:30:00Z"), 9.9),
                                                                                      new RateSegment(Instant.parse("2026-07-01T04:30:00Z"), to, 34.12));
    }

    @Test
    void dayAndNightRates_splitABandWhereTheRateChanges() {
        Instant from = Instant.parse("2026-12-31T23:30:00Z");
        Instant to = Instant.parse("2027-01-01T01:00:00Z");
        when(regionService.getDayUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-11-05T00:00:00Z", 34.12))));
        when(regionService.getNightUnitRates(PRODUCT, TARIFF, from, to))
                .thenReturn(completedFuture(List.of(createRate("2026-11-05T00:00:00Z", "2027-01-01T00:00:00Z", 9.9),
                                                    createRate("2027-01-01T00:00:00Z", 8.5))));

        assertThat(fetch(RateSchedule.DAY_AND_NIGHT_RATES, from, to)).containsExactly(new RateSegment(from, Instant.parse("2027-01-01T00:00:00Z"), 9.9),
                                                                                      new RateSegment(Instant.parse("2027-01-01T00:00:00Z"), to, 8.5));
    }

    /// The night row ends at 02:00 with no successor, and the day row starts only at 06:00, so neither the rest of the night nor the start of the day has a
    /// segment.
    @Test
    void dayAndNightRates_makeNoSegmentWhereNoRowCovers() {
        Instant from = Instant.parse("2026-11-21T00:00:00Z");
        Instant to = Instant.parse("2026-11-22T00:00:00Z");
        when(regionService.getDayUnitRates(PRODUCT, TARIFF, from, to)).thenReturn(completedFuture(List.of(createRate("2026-11-21T06:00:00Z", 34.12))));
        when(regionService.getNightUnitRates(PRODUCT, TARIFF, from, to))
                .thenReturn(completedFuture(List.of(createRate("2026-11-05T00:00:00Z", "2026-11-21T02:00:00Z", 9.9))));

        assertThat(fetch(RateSchedule.DAY_AND_NIGHT_RATES, from, to)).containsExactly(
                new RateSegment(from, Instant.parse("2026-11-21T02:00:00Z"), 9.9),
                new RateSegment(Instant.parse("2026-11-21T06:00:00Z"), Instant.parse("2026-11-21T23:30:00Z"), 34.12));
    }

    private List<RateSegment> fetch(RateSchedule schedule, Instant from, Instant to) {
        CompletableFuture<List<RateSegment>> segments = schedule.fetchSegments(regionService, PRODUCT, TARIFF, from, to, directExecutor());
        assertThat(segments).succeedsWithin(Duration.ZERO);
        return segments.getNow(null);
    }
}
