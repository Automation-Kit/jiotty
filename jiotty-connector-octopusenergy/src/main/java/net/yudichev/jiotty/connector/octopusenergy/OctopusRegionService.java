package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Per-region handle on Octopus data that is shared across all users in that region — tariff rates, standing charges. Obtain via [OctopusEnergy#region].
/// Callers own this handle's lifecycle; closing releases the underlying resources held on their behalf and a subsequent [OctopusEnergy#region] call with the
/// same letter returns a fresh handle.
public interface OctopusRegionService extends Closeable {
    /// Returns the half-hourly standard unit rates for the `(productCode, tariffCode)` pair over `[from, to]`. The returned future fails if `tariffCode`'s
    /// trailing region letter does not match the region this handle is bound to.
    CompletableFuture<List<StandardUnitRate>> getStandardUnitRates(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the standing charges for the `(productCode, tariffCode)` pair over `[from, to]`. Each entry covers its own `[validFrom, validTo]` sub-window —
    /// typically one or two entries per tariff per year. The returned future fails if `tariffCode`'s trailing region letter does not match the region this
    /// handle is bound to.
    CompletableFuture<List<StandingCharge>> getStandingCharges(String productCode, String tariffCode, Instant from, Instant to);
}
