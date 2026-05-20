package net.yudichev.jiotty.analyticscache;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.analyticscache.TimeSeriesCache.Scope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

final class TimeSeriesStreamImpl<T> implements TimeSeriesStream<T> {
    private static final Logger logger = LogManager.getLogger(TimeSeriesStreamImpl.class);

    private final TimeSeriesCacheImpl cache;
    private final String streamId;
    private final Scope scope;
    private final Resolution resolution;
    private final TypeToken<T> type;
    private final Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation;
    private final Duration slotStep;
    private final long resolutionStepSeconds;
    private final String logPrefix;

    TimeSeriesStreamImpl(TimeSeriesCacheImpl cache,
                         String streamId,
                         Scope scope,
                         Resolution resolution,
                         TypeToken<T> type,
                         Function<SortedSet<Instant>, CompletableFuture<Map<Instant, T>>> slotsComputation) {
        this.cache = checkNotNull(cache, "cache");
        this.streamId = checkNotNull(streamId, "streamId");
        this.scope = checkNotNull(scope, "scope");
        this.resolution = checkNotNull(resolution, "resolution");
        this.type = checkNotNull(type, "type");
        this.slotsComputation = checkNotNull(slotsComputation, "slotsComputation");
        slotStep = resolution.step();
        resolutionStepSeconds = slotStep.toSeconds();
        logPrefix = "[" + scope + "][" + streamId + "]";
    }

    String streamId() {
        return streamId;
    }

    Scope scope() {
        return scope;
    }

    Resolution resolution() {
        return resolution;
    }

    TypeToken<T> type() {
        return type;
    }

    @Override
    public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
        checkSlotAligned(fromInclusive, "fromInclusive");
        checkSlotAligned(toInclusive, "toInclusive");
        checkArgument(!fromInclusive.isAfter(toInclusive), "fromInclusive (%s) must be <= toInclusive (%s)", fromInclusive, toInclusive);
        return cache.readRange(this, fromInclusive, toInclusive)
                    .thenCompose(hits -> fillMisses(fromInclusive, toInclusive, hits));
    }

    private CompletableFuture<ImmutableMap<Instant, T>> fillMisses(Instant fromInclusive, Instant toInclusive, Map<Instant, T> hits) {
        BitmapSlotSet missingSlots = listMissingSlots(fromInclusive, toInclusive, hits);
        logger.debug("{} readRange {}..{} hits={} misses={}", logPrefix, fromInclusive, toInclusive, hits.size(), missingSlots.size());
        if (missingSlots.isEmpty()) {
            return CompletableFuture.completedFuture(buildOrderedMap(fromInclusive, toInclusive, hits, Map.of()));
        }
        return slotsComputation.apply(missingSlots)
                               .thenCompose(computedSlots -> writeBackAndMerge(fromInclusive, toInclusive, hits, missingSlots, computedSlots));
    }

    private CompletableFuture<ImmutableMap<Instant, T>> writeBackAndMerge(Instant fromInclusive,
                                                                          Instant toInclusive,
                                                                          Map<Instant, T> hits,
                                                                          BitmapSlotSet missingSlots,
                                                                          Map<Instant, T> computedSlots) {
        // Lazy view: filtering happens on iteration / lookup, no copy of the underlying map. `missingSlots.contains(...)` is O(1) on the bitmap, so
        //  over-fetched entries the lambda returned outside the missing set fall out for free — those slots are already cached at known values and we
        //  don't want surprise overwrites.
        Map<Instant, T> computedValues = Maps.filterKeys(computedSlots, missingSlots::contains);
        if (computedValues.isEmpty()) {
            return CompletableFuture.completedFuture(buildOrderedMap(fromInclusive, toInclusive, hits, Map.of()));
        }
        return cache.writeBatch(this, computedValues)
                    .thenApply(_ -> buildOrderedMap(fromInclusive, toInclusive, hits, computedValues));
    }

    private BitmapSlotSet listMissingSlots(Instant fromInclusive, Instant toInclusive, Map<Instant, T> hits) {
        long deltaSeconds = toInclusive.getEpochSecond() - fromInclusive.getEpochSecond();
        int capacity = Math.toIntExact(deltaSeconds / resolutionStepSeconds + 1);
        var slots = new BitmapSlotSet(fromInclusive, resolutionStepSeconds, capacity);
        slots.setAll();
        for (Instant hit : hits.keySet()) {
            int idx = slots.indexOf(hit);
            if (idx >= 0) {
                slots.clearAt(idx);
            }
        }
        return slots;
    }

    private ImmutableMap<Instant, T> buildOrderedMap(Instant fromInclusive,
                                                     Instant toInclusive,
                                                     Map<Instant, T> hits,
                                                     Map<Instant, T> computedValues) {
        var out = ImmutableMap.<Instant, T>builderWithExpectedSize(hits.size() + computedValues.size());
        for (Instant slot = fromInclusive; !slot.isAfter(toInclusive); slot = slot.plus(slotStep)) {
            T hitValue = hits.get(slot);
            T value = hitValue != null ? hitValue : computedValues.get(slot);
            if (value != null) {
                out.put(slot, value);
            }
        }
        return out.build();
    }

    /// Alignment guard. A slot Instant must have no sub-second component and sit on a multiple of the configured resolution's step in epoch seconds — the same
    /// grid that range reads iterate over, so every accepted slot is reachable on subsequent reads.
    private void checkSlotAligned(Instant slot, String name) {
        checkNotNull(slot, name);
        checkArgument(slot.getNano() == 0 && slot.getEpochSecond() % resolutionStepSeconds == 0,
                      "%s %s is not aligned to %s", name, slot, resolution);
    }
}
