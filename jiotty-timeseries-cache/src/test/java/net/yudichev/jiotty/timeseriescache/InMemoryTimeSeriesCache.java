package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/// In-memory [TimeSeriesCache]. Used by tests, and by deployments that don't have a persistence layer (data is lost on process restart).
public final class InMemoryTimeSeriesCache implements TimeSeriesCache {
    private final StreamDefinitions definitions = new StreamDefinitions();
    /// Guards [#rowsBySlotByStreamKey].
    private final Object lock = new Object();
    /// A present Optional is a cached value; an empty Optional is a negative-cache tombstone. Both count as a hit, so a tombstoned slot is never recomputed.
    private final Map<StreamKey, Map<Instant, Optional<?>>> rowsBySlotByStreamKey = new HashMap<>();

    @Override
    public <T> TimeSeriesStream<T> defineStream(String streamId,
                                                Scope scope,
                                                Resolution resolution,
                                                TypeToken<T> type,
                                                int schemaVersion,
                                                Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        // This in-memory cache keys purely by slot and never re-decodes, so it does not evict on version change; validate the version for contract parity.
        CacheSchemaVersions.checkVersion(schemaVersion);
        definitions.register(streamId, scope, resolution, type);
        return new InMemoryTimeSeriesStream<>(new StreamKey(streamId, scope), resolution, slotsComputation);
    }

    @Override
    public CompletableFuture<Integer> deleteAllForScope(Scope scope) {
        definitions.forgetScope(scope);
        return CompletableFuture.completedFuture(deleteRowsMatching(key -> key.scope().equals(scope)));
    }

    @Override
    public CompletableFuture<Integer> deleteAllForStream(String streamId) {
        definitions.forgetStream(streamId);
        return CompletableFuture.completedFuture(deleteRowsMatching(key -> key.streamId().equals(streamId)));
    }

    @Override
    public CompletableFuture<Integer> deleteOlderThan(Instant cutoffExclusive) {
        int deletedCount = 0;
        synchronized (lock) {
            for (Map<Instant, Optional<?>> rowsBySlot : rowsBySlotByStreamKey.values()) {
                int before = rowsBySlot.size();
                rowsBySlot.keySet().removeIf(slot -> slot.isBefore(cutoffExclusive));
                deletedCount += before - rowsBySlot.size();
            }
        }
        return CompletableFuture.completedFuture(deletedCount);
    }

    private int deleteRowsMatching(Predicate<StreamKey> keyPredicate) {
        int deletedCount = 0;
        synchronized (lock) {
            for (Iterator<Map.Entry<StreamKey, Map<Instant, Optional<?>>>> iterator = rowsBySlotByStreamKey.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<StreamKey, Map<Instant, Optional<?>>> entry = iterator.next();
                if (keyPredicate.test(entry.getKey())) {
                    deletedCount += entry.getValue().size();
                    iterator.remove();
                }
            }
        }
        return deletedCount;
    }

    private record StreamKey(String streamId, Scope scope) {}

    /// Reads and writes the rows of its `(streamId, scope)` through the cache, so every handle on that key sees the same values.
    private final class InMemoryTimeSeriesStream<T> implements TimeSeriesStream<T> {
        private final StreamKey key;
        private final Resolution resolution;
        private final Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation;

        InMemoryTimeSeriesStream(StreamKey key,
                                 Resolution resolution,
                                 Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
            this.key = key;
            this.resolution = resolution;
            this.slotsComputation = slotsComputation;
        }

        @SuppressWarnings("unchecked") // the rows under a key only ever hold values of the type its definition fixed
        @Override
        public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
            Duration step = resolution.step();
            var hits = new HashMap<Instant, Optional<T>>();
            var missingSlots = new TreeSet<Instant>();
            synchronized (lock) {
                Map<Instant, Optional<?>> rowsBySlot = rowsBySlotByStreamKey.getOrDefault(key, Map.of());
                for (Instant slot = fromInclusive; !slot.isAfter(toInclusive); slot = slot.plus(step)) {
                    Optional<?> value = rowsBySlot.get(slot);
                    if (value != null) {
                        hits.put(slot, (Optional<T>) value);
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
                    Map<Instant, Optional<?>> rowsBySlot = rowsBySlotByStreamKey.computeIfAbsent(key, _ -> new HashMap<>());
                    for (Instant slot : missingSlots) {
                        // A present value and an Optional.empty() tombstone are both retained (so the tombstone suppresses recomputation); a slot the
                        // computation omitted entirely stays absent and is recomputed on the next read.
                        Optional<T> value = computedSlots.get(slot);
                        if (value != null) {
                            rowsBySlot.put(slot, value);
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
                Map<Instant, Optional<?>> rowsBySlot = rowsBySlotByStreamKey.get(key);
                // rowsBySlot holds both stored values and Optional.empty() tombstones; either counts as cached.
                return CompletableFuture.completedFuture(rowsBySlot != null && rowsBySlot.containsKey(slot));
            }
        }
    }
}
