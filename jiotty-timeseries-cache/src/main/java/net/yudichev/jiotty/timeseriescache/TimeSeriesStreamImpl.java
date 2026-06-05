package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheUtil.buildOrderedMap;

final class TimeSeriesStreamImpl<T> implements TimeSeriesStream<T> {
    private static final Logger logger = LogManager.getLogger(TimeSeriesStreamImpl.class);

    private final TimeSeriesCacheImpl cache;
    private final String streamId;
    private final Scope scope;
    private final Resolution resolution;
    private final TypeToken<T> type;
    private final Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation;
    private final int schemaVersion;
    private final Duration slotStep;
    private final long resolutionStepSeconds;
    private final String logPrefix;

    TimeSeriesStreamImpl(TimeSeriesCacheImpl cache,
                         String streamId,
                         Scope scope,
                         Resolution resolution,
                         TypeToken<T> type,
                         int schemaVersion,
                         Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        this.cache = checkNotNull(cache, "cache");
        this.streamId = checkNotNull(streamId, "streamId");
        this.scope = checkNotNull(scope, "scope");
        this.resolution = checkNotNull(resolution, "resolution");
        this.type = checkNotNull(type, "type");
        this.slotsComputation = checkNotNull(slotsComputation, "slotsComputation");
        this.schemaVersion = schemaVersion;
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

    int schemaVersion() {
        return schemaVersion;
    }

    @Override
    public CompletableFuture<ImmutableMap<Instant, T>> readRange(Instant fromInclusive, Instant toInclusive) {
        checkSlotAligned(fromInclusive, "fromInclusive");
        checkSlotAligned(toInclusive, "toInclusive");
        checkArgument(!fromInclusive.isAfter(toInclusive), "fromInclusive (%s) must be <= toInclusive (%s)", fromInclusive, toInclusive);
        return cache.readRange(this, fromInclusive, toInclusive)
                    .thenCompose(hits -> fillMisses(fromInclusive, toInclusive, hits));
    }

    @Override
    public CompletableFuture<Boolean> isCached(Instant slot) {
        checkSlotAligned(slot, "slot");
        // A single-slot read returns the slot as a key for both a stored value and a tombstone (the read counts tombstones as hits), and runs no miss-fill.
        return cache.readRange(this, slot, slot).thenApply(hits -> hits.containsKey(slot));
    }

    private CompletableFuture<ImmutableMap<Instant, T>> fillMisses(Instant fromInclusive, Instant toInclusive, Map<Instant, Optional<T>> hits) {
        BitmapSlotSet missingSlots = listMissingSlots(fromInclusive, toInclusive, hits);
        logger.debug("{} readRange {}..{} hits={} misses={}", logPrefix, fromInclusive, toInclusive, hits.size(), missingSlots.size());
        if (missingSlots.isEmpty()) {
            return CompletableFuture.completedFuture(buildOrderedMap(fromInclusive, toInclusive, slotStep, hits, Map.of()));
        }
        return slotsComputation.apply(missingSlots)
                               .thenCompose(computedSlots -> writeBackAndMerge(fromInclusive, toInclusive, hits, missingSlots, computedSlots));
    }

    private CompletableFuture<ImmutableMap<Instant, T>> writeBackAndMerge(Instant fromInclusive,
                                                                          Instant toInclusive,
                                                                          Map<Instant, Optional<T>> hits,
                                                                          BitmapSlotSet missingSlots,
                                                                          Map<Instant, Optional<T>> computedSlots) {
        // Lazy view: filtering happens on iteration / lookup, no copy of the underlying map. `missingSlots.contains(...)` is O(1) on the bitmap, so
        //  over-fetched entries the lambda returned outside the missing set fall out for free — those slots are already cached at known values and we
        //  don't want surprise overwrites. Both present values and Optional.empty() tombstones inside the missing set are written back, so a slot the
        //  computation declared definitively empty is cached as a tombstone and never recomputed; a slot it omitted entirely stays absent and is recomputed.
        Map<Instant, Optional<T>> computedSlotsInRange = Maps.filterKeys(computedSlots, missingSlots::contains);
        if (computedSlotsInRange.isEmpty()) {
            return CompletableFuture.completedFuture(buildOrderedMap(fromInclusive, toInclusive, slotStep, hits, Map.of()));
        }
        return cache.writeBatch(this, computedSlotsInRange)
                    .thenApply(_ -> buildOrderedMap(fromInclusive, toInclusive, slotStep, hits, computedSlotsInRange));
    }

    private BitmapSlotSet listMissingSlots(Instant fromInclusive, Instant toInclusive, Map<Instant, Optional<T>> hits) {
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

    /// Alignment guard. A slot Instant must have no sub-second component and sit on a multiple of the configured resolution's step in epoch seconds — the same
    /// grid that range reads iterate over, so every accepted slot is reachable on subsequent reads.
    private void checkSlotAligned(Instant slot, String name) {
        checkNotNull(slot, name);
        checkArgument(slot.getNano() == 0 && slot.getEpochSecond() % resolutionStepSeconds == 0,
                      "%s %s is not aligned to %s", name, slot, resolution);
    }
}
