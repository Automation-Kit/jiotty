package net.yudichev.jiotty.analyticscache;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.analyticscache.TimeSeriesCache.Scope;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

/// Typed per-stream handle onto a [TimeSeriesCache]. Obtain via [TimeSeriesCache#defineStream], which fixes the stream's identity, [Scope], [Resolution],
/// element type, and miss-fill `slotsComputation`. Reads through [#readRange] iterate cache hits and fall back to the `slotsComputation` argument passed to
/// [TimeSeriesCache#defineStream] for misses, writing successful computations back to cache.
public interface TimeSeriesStream<T> {
    /// Returns a future that completes with a map of `slotStart -> value` for every slot in `[fromInclusive, toInclusive]` (inclusive both) where either the
    /// cache has a hit or the `slotsComputation` argument passed to [TimeSeriesCache#defineStream] returned a value for it. Map keys are in chronological
    /// order. Slots omitted from the lambda's returned map are absent from the result; the cache stores only values the lambda actually returned, so subsequent
    /// calls re-request the same slots.
    ///
    /// @throws IllegalArgumentException if `fromInclusive` or `toInclusive` is not on a multiple of the configured resolution's step (including sub-second
    /// precision), or if `fromInclusive` is after `toInclusive`
    /// @implSpec Implementations MUST enforce the slot-alignment precondition on every call so off-grain rows can never be written into the cache.
    CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive);
}
