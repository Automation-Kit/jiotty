package net.yudichev.jiotty.energy.octopus;

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
import java.util.Optional;
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
    // Cache schema versions for the Octopus connector DTOs cached by these streams. The DTOs live in jiotty-connector-octopusenergy, which must not depend on
    // the cache module to carry a @CacheSchemaVersion, so the version is declared here at stream-definition. Bump the relevant one when that DTO's serialized
    // shape changes (a renamed/removed/retyped field) so the old cached rows recompute instead of mis-decoding. They version independently.
    private static final int RATES_SCHEMA_VERSION = 1;
    private static final int STANDING_CHARGES_SCHEMA_VERSION = 1;
    private static final int CONSUMPTION_SCHEMA_VERSION = 1;

    private OctopusStreams() {
    }

    /// Defines (or returns the existing) `octopus.rates:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandardUnitRates].
    /// Each half-hour slot maps to whichever returned rate's `[validFrom, validTo)` window contains it: an exact match for half-hourly tariffs (each
    /// window is one half-hour), the covering window for flat or multi-day tariffs. A slot no returned rate covers is left absent (uncached) so a future read
    /// requests it again: an uncovered slot can be a future or not-yet-published half-hour whose rate will exist later, so it must stay re-queryable rather
    /// than be recorded as definitively empty.
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
                RATES_SCHEMA_VERSION,
                slots -> regionService.getStandardUnitRates(productCode, tariffCode, slots.first(), slots.last().plus(HALF_HOUR))
                                      .thenApply(rates -> mapSlotsToCoveringWindow(
                                              slots, rates, StandardUnitRate::validFrom, StandardUnitRate::validTo, false)));
    }

    /// Defines (or returns the existing) `octopus.standing:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandingCharges]. Each daily
    /// slot maps to whichever returned charge's `[validFrom, validTo)` window contains the slot's start instant; slots not covered by any returned charge are
    /// tombstoned (negative-cached) so a future call does not re-request them — this stream is only ever read for settled past days, so an uncovered slot is
    /// definitively empty.
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
                STANDING_CHARGES_SCHEMA_VERSION,
                missingSlots -> regionService.getStandingCharges(productCode, tariffCode, missingSlots.first(), missingSlots.last().plus(ONE_DAY))
                                             .thenApply(charges -> mapSlotsToCoveringWindow(missingSlots,
                                                                                            charges,
                                                                                            StandingCharge::validFrom,
                                                                                            charge -> charge.validTo().orElse(null),
                                                                                            true)));
    }

    /// Defines (or returns the existing) `octopus.consumption:<mpan>:<meterSerial>` stream backed by [OctopusAccountService#getConsumption]. The stream is
    /// scoped to the supplied `userId` since consumption data is per-account. Requested slots with no matching consumption row are tombstoned (negative-cached)
    /// so a future call does not re-request them — this stream is only ever read for settled past days, so a slot with no published reading is definitively
    /// empty.
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
                CONSUMPTION_SCHEMA_VERSION,
                slots -> accountService.getConsumption(mpan, meterSerial, slots.first(), slots.last().plus(HALF_HOUR))
                                       .thenApply(rows -> indexByTombstoningMisses(slots, rows, ConsumptionRow::intervalStart)));
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

    /// Builds a `slot → Optional<value>` map by indexing each returned row by its `slotKeyExtractor`, then resolving every requested slot: a slot with a
    /// matching row carries [Optional#of] that row; a requested slot with no matching row carries [Optional#empty] — a negative-cache tombstone, so the slot is
    /// not re-requested. Rows whose key falls outside `slots` are dropped.
    private static <T> Map<Instant, Optional<T>> indexByTombstoningMisses(SortedSet<Instant> slots, List<T> rows, Function<T, Instant> slotKeyExtractor) {
        // Seed every requested slot with a tombstone, then overwrite the slots a row lands on. One map, O(slots + rows): after seeding, the result's keys ARE
        // the requested slots, so replace() updates a seeded slot in a single O(1) lookup and no-ops for a row whose key isn't a requested slot — no separate
        // index, no per-row O(log) SortedSet membership probe.
        var result = HashMap.<Instant, Optional<T>>newHashMap(slots.size());
        for (Instant slot : slots) {
            result.put(slot, Optional.empty());
        }
        for (T row : rows) {
            result.replace(slotKeyExtractor.apply(row), Optional.of(row));
        }
        return result;
    }

    /// Maps each requested slot to the row whose `[validFrom, validTo)` window contains it, where a null `validToExclusive` means "open-ended" (the current
    /// row). Rows are indexed by `validFrom` in a [NavigableMap], so each slot resolves with one floor lookup: an exact match when a window starts on the slot
    /// (half-hourly data), the covering window otherwise (flat or multi-day data). Windows are non-overlapping, so the latest window starting at-or-before the
    /// slot is the one covering it. A covered slot carries [Optional#of] the row. A slot no window covers carries [Optional#empty] (a negative-cache tombstone,
    /// so it is not re-requested) when `tombstoneMisses` is true, or is omitted entirely (so the cache re-requests it later) when false. The `byStart` index is
    /// the intermediate that earns its keep: it turns the per-slot covering lookup from an O(slots·rows) scan into O(slots·log rows).
    private static <T> Map<Instant, Optional<T>> mapSlotsToCoveringWindow(SortedSet<Instant> requestedMissingSlots,
                                                                          List<T> rows,
                                                                          Function<T, Instant> validFrom,
                                                                          Function<T, @Nullable Instant> validToExclusive,
                                                                          boolean tombstoneMisses) {
        NavigableMap<Instant, T> byStart = new TreeMap<>();
        for (T row : rows) {
            byStart.put(validFrom.apply(row), row);
        }
        var result = HashMap.<Instant, Optional<T>>newHashMap(requestedMissingSlots.size());
        for (Instant slot : requestedMissingSlots) {
            Map.Entry<Instant, T> covering = byStart.floorEntry(slot);
            T value = null;
            if (covering != null) {
                Instant end = validToExclusive.apply(covering.getValue());
                if (end == null || slot.isBefore(end)) {
                    value = covering.getValue();
                }
            }
            if (value != null) {
                result.put(slot, Optional.of(value));
            } else if (tombstoneMisses) {
                result.put(slot, Optional.empty());
            }
        }
        return result;
    }
}
