package net.yudichev.jiotty.energy.octopus;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
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
    /// Octopus' name for the payment method of the rows that give the prices charged to a customer who pays by direct debit.
    public static final String DIRECT_DEBIT = "DIRECT_DEBIT";
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    private static final Duration ONE_DAY = Duration.ofDays(1);
    // Cache schema versions for the Octopus connector DTOs cached by these streams. The DTOs live in jiotty-connector-octopusenergy, which must not depend on
    // the cache module to carry a @CacheSchemaVersion, so the version is declared here at stream-definition. Bump the relevant one when that DTO's serialised
    // shape changes (a renamed/removed/retyped field), or when the mapping from response rows to slots changes, so the old cached rows recompute instead of
    // mis-decoding or keeping a wrong row. They version independently.
    private static final int RATES_SCHEMA_VERSION = 3;
    private static final int STANDING_CHARGES_SCHEMA_VERSION = 2;
    private static final int CONSUMPTION_SCHEMA_VERSION = 1;

    private OctopusStreams() {
    }

    /// Returns a handle on the `octopus.rates:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandardUnitRates].
    /// Each half-hour slot maps to whichever returned rate's `[validFrom, validTo)` window contains it: an exact match for half-hourly tariffs (each
    /// window is one half-hour), the covering window for flat or multi-day tariffs. Only rows whose payment method is empty or {@value #DIRECT_DEBIT} are
    /// used. A slot that no returned rate covers is left absent, so a later read requests it again: its rate may not be published yet. The returned map has
    /// no entry for a slot that starts after `timeProvider`'s current instant under a rate with no end date, because Octopus can still end that rate before
    /// the slot.
    ///
    /// @param timeProvider read at every fetch, which runs long after the stream is defined
    public static TimeSeriesStream<StandardUnitRate> ratesStream(TimeSeriesCache cache,
                                                                 OctopusRegionService regionService,
                                                                 CurrentDateTimeProvider timeProvider,
                                                                 String productCode,
                                                                 String tariffCode) {
        char regionLetter = validateRegionScopedArgs(cache, regionService, timeProvider, productCode, tariffCode);
        return cache.defineStream(
                "octopus.rates:" + productCode + ":" + tariffCode,
                Scope.region(String.valueOf(regionLetter)),
                Resolution.halfHourly(),
                new TypeToken<>() {},
                RATES_SCHEMA_VERSION,
                slots -> regionService.getStandardUnitRates(productCode, tariffCode, slots.first(), slots.last().plus(HALF_HOUR))
                                      .thenApply(rates -> mapSlotsToCoveringWindow(slots,
                                                                                   rates,
                                                                                   StandardUnitRate::validFrom,
                                                                                   rate -> rate.validTo().orElse(null),
                                                                                   StandardUnitRate::paymentMethod,
                                                                                   timeProvider.currentInstant(),
                                                                                   false)));
    }

    /// Returns a handle on the `octopus.standing:<productCode>:<tariffCode>` stream backed by [OctopusRegionService#getStandingCharges]. Each daily
    /// slot maps to whichever returned charge's `[validFrom, validTo)` window contains the slot's start instant. Only rows whose payment method is empty or
    /// {@value #DIRECT_DEBIT} are used. A slot that no returned charge covers is cached as empty, since this stream is read only for past days, whose
    /// charges Octopus have already published. A slot that starts after `timeProvider`'s current
    /// instant and falls under a charge with no end date is left absent, because Octopus can still end that charge before the slot.
    ///
    /// @param timeProvider read at every fetch, which runs long after the stream is defined
    public static TimeSeriesStream<StandingCharge> standingChargesStream(TimeSeriesCache cache,
                                                                         OctopusRegionService regionService,
                                                                         CurrentDateTimeProvider timeProvider,
                                                                         String productCode,
                                                                         String tariffCode) {
        char regionLetter = validateRegionScopedArgs(cache, regionService, timeProvider, productCode, tariffCode);
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
                                                                                            StandingCharge::paymentMethod,
                                                                                            timeProvider.currentInstant(),
                                                                                            true)));
    }

    /// Returns a handle on the `octopus.consumption:<mpan>:<meterSerial>` stream backed by [OctopusAccountService#getConsumption]. The stream is
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

    /// Checks the arguments of [#ratesStream] and [#standingChargesStream] and returns [OctopusTariffCode#region] of `tariffCode`.
    private static char validateRegionScopedArgs(TimeSeriesCache cache,
                                                 OctopusRegionService regionService,
                                                 CurrentDateTimeProvider timeProvider,
                                                 String productCode,
                                                 String tariffCode) {
        checkNotNull(cache, "cache");
        checkNotNull(regionService, "regionService");
        checkNotNull(timeProvider, "timeProvider");
        checkArgument(productCode != null && !productCode.isBlank(), "productCode must be non-blank");
        checkArgument(tariffCode != null && !tariffCode.isBlank(), "tariffCode must be non-blank");
        return OctopusTariffCode.region(tariffCode);
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

    /// Maps each requested slot to the row whose `[validFrom, validTo)` window contains it, where a null `validToExclusive` means the row has no end. Only
    /// the rows of [#indexDirectDebitRowsByStart] are used, and each slot is found with one floor lookup on their start. A covered slot carries [Optional#of]
    /// the row, except a slot after `now` under a row with no end, which is omitted. A slot that no row covers carries [Optional#empty] when
    /// `tombstoneMisses` is true, and is omitted when it is false.
    ///
    /// @param <T> the row type: a rate or a standing charge
    private static <T> Map<Instant, Optional<T>> mapSlotsToCoveringWindow(SortedSet<Instant> requestedMissingSlots,
                                                                          List<T> rows,
                                                                          Function<T, Instant> validFrom,
                                                                          Function<T, @Nullable Instant> validToExclusive,
                                                                          Function<T, Optional<String>> paymentMethod,
                                                                          Instant now,
                                                                          boolean tombstoneMisses) {
        NavigableMap<Instant, T> rowsByStart = indexDirectDebitRowsByStart(rows, validFrom, paymentMethod);
        var result = HashMap.<Instant, Optional<T>>newHashMap(requestedMissingSlots.size());
        for (Instant slot : requestedMissingSlots) {
            Map.Entry<Instant, T> coveringEntry = rowsByStart.floorEntry(slot);
            Instant end = null;
            boolean covered = false;
            if (coveringEntry != null) {
                end = validToExclusive.apply(coveringEntry.getValue());
                covered = end == null || slot.isBefore(end);
            }
            if (!covered) {
                if (tombstoneMisses) {
                    result.put(slot, Optional.empty());
                }
            } else if (end != null || !slot.isAfter(now)) {
                result.put(slot, Optional.of(coveringEntry.getValue()));
            }
        }
        return result;
    }

    /// @param <T> the row type: a rate or a standing charge
    /// @return the rows for which [#appliesToDirectDebit] holds, by their start; their windows do not overlap
    static <T> NavigableMap<Instant, T> indexDirectDebitRowsByStart(List<T> rows, Function<T, Instant> validFrom, Function<T, Optional<String>> paymentMethod) {
        // The index by start turns each slot's lookup of its covering row into one floorEntry, O(log rows), where a scan would be O(rows) per slot.
        NavigableMap<Instant, T> rowsByStart = new TreeMap<>();
        for (T row : rows) {
            if (appliesToDirectDebit(paymentMethod.apply(row).orElse(null))) {
                rowsByStart.put(validFrom.apply(row), row);
            }
        }
        return rowsByStart;
    }

    /// Whether a rate or standing-charge row with this `paymentMethod` is the row whose price a customer paying by direct debit is charged: a row for every
    /// payment method or a {@value #DIRECT_DEBIT} one. Octopus price some tariffs per payment method and return a row for each over the same window.
    ///
    /// @param paymentMethod the row's payment method, `null` for a row that applies to every payment method
    private static boolean appliesToDirectDebit(@Nullable String paymentMethod) {
        return paymentMethod == null || paymentMethod.equals(DIRECT_DEBIT);
    }
}
