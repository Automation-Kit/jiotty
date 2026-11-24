package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.energy.octopus.IntelligentOctopusGoProducts.OFF_PEAK_END;
import static net.yudichev.jiotty.energy.octopus.IntelligentOctopusGoProducts.OFF_PEAK_START;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.UK_ZONE;

/// How the rate rows of a tariff with published rates are fetched and turned into [RateSegment]s. Only the rows for every payment method and the
/// {@value OctopusStreams#DIRECT_DEBIT} rows are used.
public enum RateSchedule {
    /// The rows that [OctopusRegionService#getStandardUnitRates] returns, each one segment: one flat price per price-cap period, or one band of a time-of-use
    /// tariff.
    STANDARD_UNIT_RATES {
        @Override
        public CompletableFuture<List<RateSegment>> fetchSegments(OctopusRegionService regionService,
                                                                  String productCode,
                                                                  String tariffCode,
                                                                  Instant from,
                                                                  Instant to,
                                                                  Executor executor) {
            return regionService.getStandardUnitRates(productCode, tariffCode, from, to).thenApplyAsync(rates -> toSegments(rates, to), executor);
        }
    },
    /// The rows that [OctopusRegionService#getDayUnitRates] and [OctopusRegionService#getNightUnitRates] return: the night rate applies in the
    /// [IntelligentOctopusGoProducts] off-peak window and the day rate outside it.
    DAY_AND_NIGHT_RATES {
        @Override
        public CompletableFuture<List<RateSegment>> fetchSegments(OctopusRegionService regionService,
                                                                  String productCode,
                                                                  String tariffCode,
                                                                  Instant from,
                                                                  Instant to,
                                                                  Executor executor) {
            return regionService.getDayUnitRates(productCode, tariffCode, from, to)
                                .thenCombineAsync(regionService.getNightUnitRates(productCode, tariffCode, from, to),
                                                  (dayRates, nightRates) -> toBandedSegments(dayRates, nightRates, from, to),
                                                  executor);
        }
    };

    /// Fetches the rows over `[from, to]` and returns the segments they make, in time order and not overlapping. A row with no end date makes a segment
    /// that ends at `to`. Where no row covers a stretch of time, no segment does.
    ///
    /// @param executor the executor that turns the rows into segments
    public abstract CompletableFuture<List<RateSegment>> fetchSegments(OctopusRegionService regionService,
                                                                       String productCode,
                                                                       String tariffCode,
                                                                       Instant from,
                                                                       Instant to,
                                                                       Executor executor);

    private static List<RateSegment> toSegments(List<StandardUnitRate> rates, Instant to) {
        NavigableMap<Instant, StandardUnitRate> ratesByStart = indexDirectDebitRates(rates);
        ImmutableList.Builder<RateSegment> segments = ImmutableList.builderWithExpectedSize(ratesByStart.size());
        for (StandardUnitRate rate : ratesByStart.values()) {
            Instant end = rate.validTo().orElse(to);
            if (end.isAfter(rate.validFrom())) {
                segments.add(new RateSegment(rate.validFrom(), end, rate.valueIncVat()));
            }
        }
        return segments.build();
    }

    /// Splits `[from, to)` at every start and end of the off-peak window and of every row, and prices each piece with the night or day row that covers its
    /// start.
    private static List<RateSegment> toBandedSegments(List<StandardUnitRate> dayRates, List<StandardUnitRate> nightRates, Instant from, Instant to) {
        NavigableMap<Instant, StandardUnitRate> dayRatesByStart = indexDirectDebitRates(dayRates);
        NavigableMap<Instant, StandardUnitRate> nightRatesByStart = indexDirectDebitRates(nightRates);
        NavigableSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(from);
        LocalDate lastDate = to.atZone(UK_ZONE).toLocalDate();
        for (LocalDate date = from.atZone(UK_ZONE).toLocalDate(); !date.isAfter(lastDate); date = date.plusDays(1)) {
            addIfInside(boundaries, date.atTime(OFF_PEAK_END).atZone(UK_ZONE).toInstant(), from, to);
            addIfInside(boundaries, date.atTime(OFF_PEAK_START).atZone(UK_ZONE).toInstant(), from, to);
        }
        addRateBoundaries(boundaries, dayRatesByStart, from, to);
        addRateBoundaries(boundaries, nightRatesByStart, from, to);

        ImmutableList.Builder<RateSegment> segments = ImmutableList.builderWithExpectedSize(boundaries.size());
        Instant start = boundaries.pollFirst();
        while (start != null) {
            Instant nextBoundary = boundaries.pollFirst();
            Instant end = nextBoundary == null ? to : nextBoundary;
            StandardUnitRate rate = covering(IntelligentOctopusGoProducts.isOffPeak(start) ? nightRatesByStart : dayRatesByStart, start);
            if (rate != null) {
                segments.add(new RateSegment(start, end, rate.valueIncVat()));
            }
            start = nextBoundary;
        }
        return segments.build();
    }

    private static NavigableMap<Instant, StandardUnitRate> indexDirectDebitRates(List<StandardUnitRate> rates) {
        return OctopusStreams.indexDirectDebitRowsByStart(rates, StandardUnitRate::validFrom, StandardUnitRate::paymentMethod);
    }

    private static void addRateBoundaries(NavigableSet<Instant> boundaries,
                                          NavigableMap<Instant, StandardUnitRate> ratesByStart,
                                          Instant from,
                                          Instant to) {
        for (StandardUnitRate rate : ratesByStart.values()) {
            addIfInside(boundaries, rate.validFrom(), from, to);
            rate.validTo().ifPresent(end -> addIfInside(boundaries, end, from, to));
        }
    }

    private static void addIfInside(NavigableSet<Instant> boundaries, Instant instant, Instant from, Instant to) {
        if (instant.isAfter(from) && instant.isBefore(to)) {
            boundaries.add(instant);
        }
    }

    /// @return the rate that covers `instant`, `null` when none does
    private static @Nullable StandardUnitRate covering(NavigableMap<Instant, StandardUnitRate> ratesByStart, Instant instant) {
        Map.Entry<Instant, StandardUnitRate> entry = ratesByStart.floorEntry(instant);
        if (entry == null) {
            return null;
        }
        StandardUnitRate rate = entry.getValue();
        Instant end = rate.validTo().orElse(null);
        return end == null || instant.isBefore(end) ? rate : null;
    }

    /// The price over `[from, to)`.
    public record RateSegment(Instant from, Instant to, double price) {
        public RateSegment {
            checkNotNull(from);
            checkNotNull(to);
        }
    }
}
