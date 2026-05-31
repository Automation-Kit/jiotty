package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/// A non-retaining [TimeSeriesCache]: every [TimeSeriesStream#readRange] recomputes the whole requested range through the stream's `slotsComputation` and
/// stores nothing. Suitable for deployments that want the typed-stream API but must not accumulate data — notably ones where the underlying source is cheap to
/// re-query and the unbounded in-memory growth an [InMemoryTimeSeriesCache] would incur is unacceptable. For persistent caching use [TimeSeriesCacheModule]
/// (Postgres-backed); for an in-memory cache that *retains* rows (e.g. tests asserting cache hits) use the test-scoped `InMemoryTimeSeriesCache`.
public final class NoOpTimeSeriesCache implements TimeSeriesCache {
    private final Map<StreamKey, NoOpTimeSeriesStream<?>> streams = new HashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> TimeSeriesStream<T> defineStream(String streamId,
                                                             Scope scope,
                                                             Resolution resolution,
                                                             TypeToken<T> type,
                                                             Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation) {
        var key = new StreamKey(streamId, scope);
        NoOpTimeSeriesStream<?> existingStream = streams.get(key);
        if (existingStream != null) {
            if (!existingStream.resolution().equals(resolution) || !existingStream.type().getType().equals(type.getType())) {
                throw new IllegalArgumentException("conflicting redefinition of stream " + streamId + " for scope " + scope);
            }
            return (TimeSeriesStream<T>) existingStream;
        }
        var stream = new NoOpTimeSeriesStream<>(resolution, type, slotsComputation);
        streams.put(key, stream);
        return stream;
    }

    @Override
    public synchronized CompletableFuture<Integer> deleteAllForScope(Scope scope) {
        streams.keySet().removeIf(k -> k.scope().equals(scope));
        return CompletableFuture.completedFuture(0);
    }

    @Override
    public synchronized CompletableFuture<Integer> deleteAllForStream(String streamId) {
        streams.keySet().removeIf(k -> k.streamId().equals(streamId));
        return CompletableFuture.completedFuture(0);
    }

    private record StreamKey(String streamId, Scope scope) {}

    /// Recomputes the full requested range on every [#readRange] and retains nothing. `type` is retained only so [#defineStream] can reject a conflicting
    /// redefinition of the same `(streamId, scope)` with a different element type.
    private record NoOpTimeSeriesStream<T>(Resolution resolution,
                                           TypeToken<T> type,
                                           Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation)
            implements TimeSeriesStream<T> {
        @Override
        public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
            Duration step = resolution.step();
            var allSlots = new TreeSet<Instant>();
            for (Instant slot = fromInclusive; !slot.isAfter(toInclusive); slot = slot.plus(step)) {
                allSlots.add(slot);
            }
            if (allSlots.isEmpty()) {
                return CompletableFuture.completedFuture(ImmutableMap.of());
            }
            return slotsComputation.apply(allSlots)
                                   .thenApply(computedSlots -> TimeSeriesCacheUtil.buildOrderedMap(fromInclusive, toInclusive, step, Map.of(), computedSlots));
        }
    }
}
