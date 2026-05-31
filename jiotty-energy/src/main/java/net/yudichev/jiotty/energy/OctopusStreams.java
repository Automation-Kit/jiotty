package net.yudichev.jiotty.energy;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.connector.octopusenergy.ConsumptionRow;
import net.yudichev.jiotty.connector.octopusenergy.MpanRegionResolver;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.StandingCharge;
import net.yudichev.jiotty.timeseriescache.Resolution;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import net.yudichev.jiotty.timeseriescache.TimeSeriesStream;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Factory methods that register Octopus-data [TimeSeriesStream] handles on a [TimeSeriesCache]. Each method packages one ranged Octopus endpoint behind a
/// typed, cache-backed stream so a single fetch services every cache miss in the requested range, and subsequent reads hit the cache.
///
/// Three streams are exposed:
///
/// - **`octopus.rates:<productCode>:<tariffCode>`** — region-scoped, half-hourly, [StandardUnitRate]. Region is derived from the tariff code's trailing letter
/// ([MpanRegionResolver] shape).
/// - **`octopus.standing:<productCode>:<tariffCode>`** — region-scoped, daily, [StandingCharge]. Each cached day-slot is the standing charge whose `[validFrom,
/// validTo)` window contains the slot's start instant.
/// - **`octopus.consumption:<mpan>:<meterSerial>`** — user-scoped, half-hourly, [ConsumptionRow].
///
/// Callers supply the already-acquired connector handle ([OctopusRegionService] / [OctopusAccountService]); the factory does not own the handle's lifecycle.
public final class OctopusStreams {
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    private static final Duration ONE_DAY = Duration.ofDays(1);

    private OctopusStreams() {
    }

    /// Defines (or returns the existing) `octopus.rates:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandardUnitRates].
    /// Each half-hour slot maps to whichever returned rate's `[validFrom, validTo)` window contains it: an exact match for half-hourly tariffs (each
    /// window is one half-hour), the covering window for flat or multi-day tariffs. Slots no returned rate covers are omitted so a future call
    /// re-requests them.
    public static TimeSeriesStream<StandardUnitRate> ratesStream(TimeSeriesCache cache,
                                                                 OctopusRegionService regionService,
                                                                 String productCode,
                                                                 String tariffCode) {
        char regionLetter = validateRegionScopedArgs(cache, regionService, productCode, tariffCode);
        return cache.defineStream(
                "octopus.rates:" + productCode + ":" + tariffCode,
                Scope.region(String.valueOf(regionLetter)),
                Resolution.halfHourly(),
                new TypeToken<>() {},
                slots -> regionService.getStandardUnitRates(productCode, tariffCode, slots.first(), slots.last().plus(HALF_HOUR))
                                      .thenApply(rates -> mapSlotsToCoveringWindow(
                                              slots, rates, StandardUnitRate::validFrom, StandardUnitRate::validTo)));
    }

    /// Defines (or returns the existing) `octopus.standing:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandingCharges]. Each daily
    /// slot maps to whichever returned charge's `[validFrom, validTo)` window contains the slot's start instant; slots not covered by any returned charge are
    /// omitted from the cached result so a future call re-requests them.
    public static TimeSeriesStream<StandingCharge> standingChargesStream(TimeSeriesCache cache,
                                                                         OctopusRegionService regionService,
                                                                         String productCode,
                                                                         String tariffCode) {
        char regionLetter = validateRegionScopedArgs(cache, regionService, productCode, tariffCode);
        return cache.defineStream(
                "octopus.standing:" + productCode + ":" + tariffCode,
                Scope.region(String.valueOf(regionLetter)),
                Resolution.daily(),
                new TypeToken<>() {},
                missingSlots -> regionService.getStandingCharges(productCode, tariffCode, missingSlots.first(), missingSlots.last().plus(ONE_DAY))
                                             .thenApply(charges -> mapSlotsToCoveringWindow(missingSlots,
                                                                                            charges,
                                                                                            StandingCharge::validFrom,
                                                                                            charge -> charge.validTo().orElse(null))));
    }

    /// Defines (or returns the existing) `octopus.consumption:<mpan>:<meterSerial>` stream backed by [OctopusAccountService#getConsumption]. The stream is
    /// scoped to the supplied `userId` since consumption data is per-account.
    public static TimeSeriesStream<ConsumptionRow> consumptionStream(TimeSeriesCache cache,
                                                                     OctopusAccountService accountService,
                                                                     String userId,
                                                                     String mpan,
                                                                     String meterSerial) {
        checkNotNull(cache, "cache");
        checkNotNull(accountService, "accountService");
        checkArgument(userId != null && !userId.isBlank(), "userId must be non-blank");
        checkArgument(mpan != null && !mpan.isBlank(), "mpan must be non-blank");
        checkArgument(meterSerial != null && !meterSerial.isBlank(), "meterSerial must be non-blank");
        return cache.defineStream(
                "octopus.consumption:" + mpan + ":" + meterSerial,
                Scope.user(userId),
                Resolution.halfHourly(),
                new TypeToken<>() {},
                slots -> accountService.getConsumption(mpan, meterSerial, slots.first(), slots.last().plus(HALF_HOUR))
                                       .thenApply(rows -> indexBy(slots, rows, ConsumptionRow::intervalStart)));
    }

    /// Shared precondition prelude for [#ratesStream] / [#standingChargesStream]: validates non-null cache + service and non-blank product/tariff codes, then
    /// returns the region letter derived from the tariff code's trailing character.
    private static char validateRegionScopedArgs(TimeSeriesCache cache, OctopusRegionService regionService, String productCode, String tariffCode) {
        checkNotNull(cache, "cache");
        checkNotNull(regionService, "regionService");
        checkArgument(productCode != null && !productCode.isBlank(), "productCode must be non-blank");
        checkArgument(tariffCode != null && !tariffCode.isBlank(), "tariffCode must be non-blank");
        return tariffCode.charAt(tariffCode.length() - 1);
    }

    /// Builds a `slot → value` map by indexing each returned row by its `slotKeyExtractor`, then intersecting with the requested slot set. Rows whose key falls
    /// outside `slots` are dropped; slots with no matching row are omitted from the map (so the cache marks them as still-missing).
    private static <T> Map<Instant, T> indexBy(SortedSet<Instant> slots, List<T> rows, Function<T, Instant> slotKeyExtractor) {
        var result = HashMap.<Instant, T>newHashMap(rows.size());
        for (T row : rows) {
            Instant key = slotKeyExtractor.apply(row);
            if (slots.contains(key)) {
                result.put(key, row);
            }
        }
        return result;
    }

    /// Maps each requested slot to the row whose `[validFrom, validTo)` window contains it, where a null `validToExclusive` means "open-ended" (the current
    /// row). Rows are indexed by `validFrom` in a [NavigableMap], so each slot resolves with one floor lookup: an exact match when a window starts on the slot
    /// (half-hourly data), the covering window otherwise (flat or multi-day data). Windows are non-overlapping, so the latest window starting at-or-before the
    /// slot is the one covering it. Slots no window covers are omitted, so the cache re-requests them later. The `byStart` index is the intermediate that earns
    /// its keep: it turns the per-slot covering lookup from an O(slots·rows) scan into O(slots·log rows).
    private static <T> Map<Instant, T> mapSlotsToCoveringWindow(SortedSet<Instant> requestedMissingSlots,
                                                                List<T> rows,
                                                                Function<T, Instant> validFrom,
                                                                Function<T, @Nullable Instant> validToExclusive) {
        NavigableMap<Instant, T> byStart = new TreeMap<>();
        for (T row : rows) {
            byStart.put(validFrom.apply(row), row);
        }
        var result = HashMap.<Instant, T>newHashMap(requestedMissingSlots.size());
        for (Instant slot : requestedMissingSlots) {
            Map.Entry<Instant, T> covering = byStart.floorEntry(slot);
            if (covering != null) {
                Instant end = validToExclusive.apply(covering.getValue());
                if (end == null || slot.isBefore(end)) {
                    result.put(slot, covering.getValue());
                }
            }
        }
        return result;
    }
}
