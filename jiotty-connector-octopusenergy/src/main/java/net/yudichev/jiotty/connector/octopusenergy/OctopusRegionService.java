package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Per-region handle on Octopus data that is shared across all users in that region — tariff rates, standing charges. Obtain via [OctopusEnergy#region].
/// Callers own this handle's lifecycle; closing releases the underlying resources held on their behalf and a subsequent [OctopusEnergy#region] call with the
/// same letter returns a fresh handle.
public interface OctopusRegionService extends Closeable {
    /// Returns the standard unit rates of the `(productCode, tariffCode)` pair over `[from, to]`:
    ///
    /// - one row per half hour on Agile;
    /// - one row per price band on a time-of-use tariff;
    /// - one row per price-cap period on a flat tariff.
    ///
    /// The returned future fails if `tariffCode`'s trailing region letter does not match the region this handle is bound to.
    CompletableFuture<List<StandardUnitRate>> getStandardUnitRates(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the day unit rates of a tariff with separate day and night prices over `[from, to]`, one row per price-cap period. A row gives one price for
    /// the whole of its `[validFrom, validTo]` and does not say which hours of the day the day rate covers. The returned future fails if `tariffCode`'s
    /// trailing region letter does not match the region this handle is bound to.
    CompletableFuture<List<StandardUnitRate>> getDayUnitRates(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the night unit rates of a tariff with separate day and night prices over `[from, to]`, one row per price-cap period. A row gives one price
    /// for the whole of its `[validFrom, validTo]` and does not say which hours of the day the night rate covers. The returned future fails if
    /// `tariffCode`'s trailing region letter does not match the region this handle is bound to.
    CompletableFuture<List<StandardUnitRate>> getNightUnitRates(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the standing charges for the `(productCode, tariffCode)` pair over `[from, to]`. Each entry covers its own `[validFrom, validTo]` sub-window —
    /// typically one or two entries per tariff per year. The returned future fails if `tariffCode`'s trailing region letter does not match the region this
    /// handle is bound to.
    CompletableFuture<List<StandingCharge>> getStandingCharges(String productCode, String tariffCode, Instant from, Instant to);
}
