package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/// In-memory [TimeSeriesCache]. Each [#defineStream] call returns an [InMemoryTimeSeriesStream] holding its own row map and the bound `slotsComputation`. Used
/// by tests, and by deployments that don't have a persistence layer (data is lost on process restart).
public final class InMemoryTimeSeriesCache implements TimeSeriesCache {
    private final Map<StreamKey, InMemoryTimeSeriesStream<?>> streams = new HashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public synchronized <T> TimeSeriesStream<T> defineStream(String streamId,
                                                             Scope scope,
                                                             Resolution resolution,
                                                             TypeToken<T> type,
                                                             int schemaVersion,
                                                             Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        // This in-memory cache keys purely by slot and never re-decodes, so it does not evict on version change; validate the version for contract parity.
        CacheSchemaVersions.checkVersion(schemaVersion);
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

    @Override
    public synchronized CompletableFuture<Integer> deleteOlderThan(Instant cutoffExclusive) {
        int deleted = 0;
        for (var stream : streams.values()) {
            deleted += stream.purgeOlderThan(cutoffExclusive);
        }
        // No stream-handle removal: the purge spans live streams, matching TimeSeriesCacheImpl's contract.
        return CompletableFuture.completedFuture(deleted);
    }

    private record StreamKey(String streamId, Scope scope) {}

    private static final class InMemoryTimeSeriesStream<T> implements TimeSeriesStream<T> {
        private final Scope scope;
        private final String streamId;
        private final Resolution resolution;
        private final TypeToken<T> type;
        private final Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation;
        // A present Optional is a cached value; an empty Optional is a negative-cache tombstone. Both count as a hit, so a tombstoned slot is never recomputed.
        private final Map<Instant, Optional<T>> rows = new HashMap<>();
        private final Object lock = new Object();

        InMemoryTimeSeriesStream(Scope scope,
                                 String streamId,
                                 Resolution resolution,
                                 TypeToken<T> type,
                                 Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
            this.scope = scope;
            this.streamId = streamId;
            this.resolution = resolution;
            this.type = type;
            this.slotsComputation = slotsComputation;
        }

        @Override
        public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
            Duration step = resolution.step();
            var hits = new HashMap<Instant, Optional<T>>();
            var missingSlots = new TreeSet<Instant>();
            synchronized (lock) {
                for (Instant slot = fromInclusive; !slot.isAfter(toInclusive); slot = slot.plus(step)) {
                    Optional<T> value = rows.get(slot);
                    if (value != null) {
                        hits.put(slot, value);
                    } else {
                        missingSlots.add(slot);
                    }
                }
            }
            if (missingSlots.isEmpty()) {
                return CompletableFuture.completedFuture(TimeSeriesCacheUtil.buildOrderedMap(fromInclusive, toInclusive, step, hits, Map.of()));
            }
            return slotsComputation.apply(missingSlots).thenApply(computedSlots -> {
                var computedValues = new HashMap<Instant, Optional<T>>();
                synchronized (lock) {
                    for (Instant slot : missingSlots) {
                        // A present value and an Optional.empty() tombstone are both retained (so the tombstone suppresses recomputation); a slot the
                        // computation omitted entirely stays absent and is recomputed on the next read.
                        Optional<T> value = computedSlots.get(slot);
                        if (value != null) {
                            rows.put(slot, value);
                            computedValues.put(slot, value);
                        }
                    }
                }
                return TimeSeriesCacheUtil.buildOrderedMap(fromInclusive, toInclusive, step, hits, computedValues);
            });
        }

        @Override
        public CompletableFuture<Boolean> isCached(Instant slot) {
            synchronized (lock) {
                // rows holds both stored values and Optional.empty() tombstones; either counts as cached.
                return CompletableFuture.completedFuture(rows.containsKey(slot));
            }
        }

        int purgeOlderThan(Instant cutoffExclusive) {
            synchronized (lock) {
                int before = rows.size();
                rows.keySet().removeIf(slot -> slot.isBefore(cutoffExclusive));
                return before - rows.size();
            }
        }
    }
}
