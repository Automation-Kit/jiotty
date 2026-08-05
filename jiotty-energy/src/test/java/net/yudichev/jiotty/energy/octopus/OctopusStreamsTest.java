package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.connector.octopusenergy.ConsumptionRow;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.StandingCharge;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusStreamsTest {

    private static final String PRODUCT = "AGILE-24-10-01";
    private static final String TARIFF = "E-1R-AGILE-24-10-01-A";
    private static final String MPAN = "9999999999999";
    private static final String SERIAL = "99XXX99999";
    private static final String USER = "userId";

    private TimeSeriesCache cache;
    @Mock
    private OctopusRegionService regionService;
    @Mock
    private OctopusAccountService accountService;

    @BeforeEach
    void setUp() {
        cache = new InMemoryTimeSeriesCache();
    }

    @Test
    void ratesStream_halfHourlyRates_mapEachSlotToItsWindow() {
        // readRange is inclusive both ends; from=00:00, to=00:30 requests two half-hour slots: 00:00 + 00:30.
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T00:30:00Z");
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        rate("2024-01-15T00:00:00Z", "2024-01-15T00:30:00Z", 14.0),
                        rate("2024-01-15T00:30:00Z", "2024-01-15T01:00:00Z", 15.0),
                        // Row whose window covers no requested slot — must not appear in the result.
                        rate("2024-01-15T01:00:00Z", "2024-01-15T01:30:00Z", 16.0))));

        TimeSeriesStream<StandardUnitRate> stream = OctopusStreams.ratesStream(cache, regionService, PRODUCT, TARIFF);
        ImmutableMap<Instant, StandardUnitRate> result = stream.readRange(from, to).join();

        assertThat(result)
                .hasEntrySatisfying(Instant.parse("2024-01-15T00:00:00Z"), rate -> assertThat(rate.valueIncVat()).isEqualTo(14.0))
                .hasEntrySatisfying(Instant.parse("2024-01-15T00:30:00Z"), rate -> assertThat(rate.valueIncVat()).isEqualTo(15.0))
                .hasSize(2);
    }

    @Test
    void ratesStream_flatTariff_mapsEverySlotToTheCoveringWindow() {
        // A non-half-hourly tariff publishes one wide rate window. Every requested half-hour slot must resolve to that covering rate even though the window
        // starts on none of them — the case the covering-window lookup adds over a plain validFrom index.
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T01:00:00Z");   // three slots: 00:00, 00:30, 01:00
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        rate("2024-01-01T00:00:00Z", "2024-02-01T00:00:00Z", 24.5))));

        TimeSeriesStream<StandardUnitRate> stream = OctopusStreams.ratesStream(cache, regionService, PRODUCT, TARIFF);
        ImmutableMap<Instant, StandardUnitRate> result = stream.readRange(from, to).join();

        assertThat(result)
                .containsOnlyKeys(Instant.parse("2024-01-15T00:00:00Z"),
                                  Instant.parse("2024-01-15T00:30:00Z"),
                                  Instant.parse("2024-01-15T01:00:00Z"))
                .allSatisfy((_, rate) -> assertThat(rate.valueIncVat()).isEqualTo(24.5));
    }

    @Test
    void ratesStream_secondReadOfSameSlots_servesFromCache_noSecondFetch() {
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T00:00:00Z");
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(rate("2024-01-15T00:00:00Z", "2024-01-15T00:30:00Z", 14.0))));

        TimeSeriesStream<StandardUnitRate> stream = OctopusStreams.ratesStream(cache, regionService, PRODUCT, TARIFF);
        stream.readRange(from, to).join();
        stream.readRange(from, to).join();

        verify(regionService, times(1)).getStandardUnitRates(any(), any(), any(), any());
    }

    @Test
    void standingChargesStream_daySlot_mapsToCoveringCharge() {
        // Daily slots — must be at start-of-day UTC for Resolution.daily() alignment.
        Instant day1 = Instant.parse("2024-01-15T00:00:00Z");
        Instant day2 = Instant.parse("2024-01-16T00:00:00Z");
        when(regionService.getStandingCharges(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        StandingCharge.builder()
                                      .setValueExcVat(40.0)
                                      .setValueIncVat(42.0)
                                      .setValidFrom(Instant.parse("2024-01-01T00:00:00Z"))
                                      .setValidTo(Instant.parse("2024-01-16T00:00:00Z"))
                                      .build(),
                        StandingCharge.builder()
                                      .setValueExcVat(45.0)
                                      .setValueIncVat(47.25)
                                      .setValidFrom(Instant.parse("2024-01-16T00:00:00Z"))
                                      // empty validTo = open-ended (current charge).
                                      .build())));

        TimeSeriesStream<StandingCharge> stream = OctopusStreams.standingChargesStream(cache, regionService, PRODUCT, TARIFF);
        ImmutableMap<Instant, StandingCharge> result = stream.readRange(day1, day2).join();

        // day1 (2024-01-15) is covered by the [2024-01-01, 2024-01-16) charge (42.0).
        // day2 (2024-01-16) is covered by the open-ended [2024-01-16, ∞) charge (47.25).
        assertThat(result)
                .hasEntrySatisfying(day1, charge -> assertThat(charge.valueIncVat()).isEqualTo(42.0))
                .hasEntrySatisfying(day2, charge -> assertThat(charge.valueIncVat()).isEqualTo(47.25));
    }

    @Test
    void consumptionStream_readRange_fetchesAndIndexesByIntervalStart() {
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T00:30:00Z");
        when(accountService.getConsumption(eq(MPAN), eq(SERIAL), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        consumption("2024-01-15T00:00:00Z", "2024-01-15T00:30:00Z", 0.345),
                        consumption("2024-01-15T00:30:00Z", "2024-01-15T01:00:00Z", 0.422))));

        TimeSeriesStream<ConsumptionRow> stream = OctopusStreams.consumptionStream(cache, accountService, USER, MPAN, SERIAL);
        ImmutableMap<Instant, ConsumptionRow> result = stream.readRange(from, to).join();

        assertThat(result)
                .hasEntrySatisfying(Instant.parse("2024-01-15T00:00:00Z"), row -> assertThat(row.consumption()).isEqualTo(0.345))
                .hasEntrySatisfying(Instant.parse("2024-01-15T00:30:00Z"), row -> assertThat(row.consumption()).isEqualTo(0.422))
                .hasSize(2);
    }

    @Test
    void ratesStream_uncoveredSlot_isNotTombstoned_andIsReFetched() {
        // The rates stream is shared with the live Agile scheduler, whose future/unpublished slots must stay re-queryable — so an uncovered slot is left
        // absent (not tombstoned). A first read returning no covering rate must therefore re-fetch on the second read.
        Instant slot = Instant.parse("2024-01-15T00:00:00Z");
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));   // no rate published yet

        TimeSeriesStream<StandardUnitRate> stream = OctopusStreams.ratesStream(cache, regionService, PRODUCT, TARIFF);
        assertThat(stream.readRange(slot, slot).join()).isEmpty();
        assertThat(stream.readRange(slot, slot).join()).isEmpty();

        verify(regionService, times(2)).getStandardUnitRates(any(), any(), any(), any());
    }

    @Test
    void consumptionStream_missingSlot_isTombstoned_andNotReFetched() {
        // A consumption slot with no published reading is tombstoned (negative-cached): excluded from the result, but the second read does not re-fetch it.
        Instant from = Instant.parse("2024-01-15T00:00:00Z");
        Instant to = Instant.parse("2024-01-15T00:30:00Z");   // two slots; the connector only returns the first
        when(accountService.getConsumption(eq(MPAN), eq(SERIAL), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of(
                        consumption("2024-01-15T00:00:00Z", "2024-01-15T00:30:00Z", 0.345))));

        TimeSeriesStream<ConsumptionRow> stream = OctopusStreams.consumptionStream(cache, accountService, USER, MPAN, SERIAL);
        assertThat(stream.readRange(from, to).join()).containsOnlyKeys(Instant.parse("2024-01-15T00:00:00Z"));
        // Second read: both the value slot and the tombstoned empty slot are hits, so the connector is not called again.
        assertThat(stream.readRange(from, to).join()).containsOnlyKeys(Instant.parse("2024-01-15T00:00:00Z"));

        verify(accountService, times(1)).getConsumption(any(), any(), any(), any());
    }

    @Test
    void standingChargesStream_uncoveredSlot_isTombstoned_andNotReFetched() {
        // A standing-charge stream is only read for settled past days, so a day no published charge covers is tombstoned and not re-requested.
        Instant day = Instant.parse("2024-01-15T00:00:00Z");
        when(regionService.getStandingCharges(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));   // no charge covers the day

        TimeSeriesStream<StandingCharge> stream = OctopusStreams.standingChargesStream(cache, regionService, PRODUCT, TARIFF);
        assertThat(stream.readRange(day, day).join()).isEmpty();
        assertThat(stream.readRange(day, day).join()).isEmpty();

        verify(regionService, times(1)).getStandingCharges(any(), any(), any(), any());
    }

    @Test
    void ratesStream_blankProductCode_throws() {
        assertThatThrownBy(() -> OctopusStreams.ratesStream(cache, regionService, "", TARIFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productCode must be non-blank");
    }

    @Test
    void consumptionStream_blankUserId_throws() {
        assertThatThrownBy(() -> OctopusStreams.consumptionStream(cache, accountService, "", MPAN, SERIAL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId must be non-blank");
    }

    private static StandardUnitRate rate(String validFrom, String validTo, double valueIncVat) {
        return StandardUnitRate.builder()
                               .setValidFrom(Instant.parse(validFrom))
                               .setValidTo(Instant.parse(validTo))
                               .setValueExcVat(valueIncVat / 1.05)
                               .setValueIncVat(valueIncVat)
                               .build();
    }

    private static ConsumptionRow consumption(String intervalStart, String intervalEnd, double kwh) {
        return ConsumptionRow.builder()
                             .setIntervalStart(Instant.parse(intervalStart))
                             .setIntervalEnd(Instant.parse(intervalEnd))
                             .setConsumption(kwh)
                             .build();
    }
}
