package net.yudichev.jiotty.analyticscache.testing;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.analyticscache.Resolution;
import net.yudichev.jiotty.analyticscache.TimeSeriesCache;
import net.yudichev.jiotty.analyticscache.TimeSeriesStream;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/// In-memory [TimeSeriesCache] for tests. Each [#defineStream] call returns an [InMemoryTimeSeriesStream] holding its own row map and the bound
/// `slotsComputation`. Sufficient for tests that exercise cache-backed flows without a real database.
public final class InMemoryTimeSeriesCache implements TimeSeriesCache {
    private final Map<StreamKey, InMemoryTimeSeriesStream<?>> streams = new HashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> TimeSeriesStream<T> defineStream(String streamId,
                                                             Scope scope,
                                                             Resolution resolution,
                                                             TypeToken<T> type,
                                                             Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation) {
        var key = new StreamKey(streamId, scope);
        InMemoryTimeSeriesStream<?> existingStream = streams.get(key);
        if (existingStream != null) {
            if (!existingStream.resolution.equals(resolution) || !existingStream.type.getType().equals(type.getType())) {
                throw new IllegalArgumentException("conflicting redefinition of stream " + streamId + " for scope " + scope);
            }
            return (TimeSeriesStream<T>) existingStream;
        }
        var stream = new InMemoryTimeSeriesStream<>(scope, streamId, resolution, type, slotsComputation);
        streams.put(key, stream);
        return stream;
    }

    @Override
    public synchronized CompletableFuture<Integer> deleteAllForScope(Scope scope) {
        int deleted = 0;
        for (var stream : streams.values()) {
            if (stream.scope.equals(scope)) {
                deleted += stream.rows.size();
                stream.rows.clear();
            }
        }
        streams.keySet().removeIf(k -> k.scope().equals(scope));
        return CompletableFuture.completedFuture(deleted);
    }

    @Override
    public synchronized CompletableFuture<Integer> deleteAllForStream(String streamId) {
        int deleted = 0;
        for (var stream : streams.values()) {
            if (stream.streamId.equals(streamId)) {
                deleted += stream.rows.size();
                stream.rows.clear();
            }
        }
        streams.keySet().removeIf(k -> k.streamId().equals(streamId));
        return CompletableFuture.completedFuture(deleted);
    }

    private record StreamKey(String streamId, Scope scope) {}

    private static final class InMemoryTimeSeriesStream<T> implements TimeSeriesStream<T> {
        private final Scope scope;
        private final String streamId;
        private final Resolution resolution;
        private final TypeToken<T> type;
        private final Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation;
        private final Map<Instant, T> rows = new HashMap<>();
        private final Object lock = new Object();

        InMemoryTimeSeriesStream(Scope scope,
                                 String streamId,
                                 Resolution resolution,
                                 TypeToken<T> type,
                                 Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation) {
            this.scope = scope;
            this.streamId = streamId;
            this.resolution = resolution;
            this.type = type;
            this.slotsComputation = slotsComputation;
        }

        @Override
        public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
            Duration step = resolution.step();
            var hits = new HashMap<Instant, T>();
            var missingSlots = new TreeSet<Instant>();
            synchronized (lock) {
                for (Instant slot = fromInclusive; !slot.isAfter(toInclusive); slot = slot.plus(step)) {
                    T value = rows.get(slot);
                    if (value != null) {
                        hits.put(slot, value);
                    } else {
                        missingSlots.add(slot);
                    }
                }
            }
            if (missingSlots.isEmpty()) {
                return CompletableFuture.completedFuture(buildOrderedMap(fromInclusive, toInclusive, step, hits, Map.of()));
            }
            return slotsComputation.apply(missingSlots).thenApply(computedSlots -> {
                var computedValues = new HashMap<Instant, T>();
                synchronized (lock) {
                    for (Instant slot : missingSlots) {
                        T value = computedSlots.get(slot);
                        if (value != null) {
                            rows.put(slot, value);
                            computedValues.put(slot, value);
                        }
                    }
                }
                return buildOrderedMap(fromInclusive, toInclusive, step, hits, computedValues);
            });
        }

        private static <T> ImmutableMap<Instant, T> buildOrderedMap(Instant from,
                                                                    Instant to,
                                                                    Duration step,
                                                                    Map<Instant, T> hits,
                                                                    Map<Instant, T> computedValues) {
            var out = ImmutableMap.<Instant, T>builderWithExpectedSize(hits.size() + computedValues.size());
            for (Instant slot = from; !slot.isAfter(to); slot = slot.plus(step)) {
                T hitValue = hits.get(slot);
                T value = hitValue != null ? hitValue : computedValues.get(slot);
                if (value != null) {
                    out.put(slot, value);
                }
            }
            return out.build();
        }
    }
}
