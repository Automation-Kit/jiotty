package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NoOpTimeSeriesCacheTest {
    private static final Instant SLOT_APR_1 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant SLOT_APR_2 = Instant.parse("2026-04-02T00:00:00Z");
    private static final Instant SLOT_APR_3 = Instant.parse("2026-04-03T00:00:00Z");
    private static final Scope SCOPE = Scope.user("u");
    private static final String STREAM_ID = "noop-stream";
    private static final TypeToken<TestValue> TYPE = TypeToken.of(TestValue.class);

    private final NoOpTimeSeriesCache cache = new NoOpTimeSeriesCache();

    @Test
    void readRange_recomputesWholeRangeEachCall_andStoresNothing() {
        var invocations = new AtomicInteger();
        var stream = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            invocations.incrementAndGet();
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                values.put(slot, Optional.of(new TestValue("v-" + slot)));
            }
            return CompletableFuture.completedFuture(values);
        });

        ImmutableMap<Instant, TestValue> first = stream.readRange(SLOT_APR_1, SLOT_APR_3).join();
        ImmutableMap<Instant, TestValue> second = stream.readRange(SLOT_APR_1, SLOT_APR_3).join();

        // Every read recomputes the full range — nothing is retained, so the second read invokes the lambda again with the same complete slot set.
        assertThat(invocations.get()).isEqualTo(2);
        assertThat(first).containsExactly(Map.entry(SLOT_APR_1, new TestValue("v-" + SLOT_APR_1)),
                                          Map.entry(SLOT_APR_2, new TestValue("v-" + SLOT_APR_2)),
                                          Map.entry(SLOT_APR_3, new TestValue("v-" + SLOT_APR_3)));
        assertThat(second).isEqualTo(first);
    }

    @Test
    void readRange_returnsOnlyPresentSlots_inChronologicalOrder() {
        var stream = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                if (!slot.equals(SLOT_APR_2)) {   // omit the middle slot
                    values.put(slot, Optional.of(new TestValue("v-" + slot)));
                }
            }
            return CompletableFuture.completedFuture(values);
        });

        ImmutableMap<Instant, TestValue> result = stream.readRange(SLOT_APR_1, SLOT_APR_3).join();

        assertThat(result.keySet()).containsExactly(SLOT_APR_1, SLOT_APR_3);
    }

    @Test
    void readRange_emptyRange_doesNotInvokeLambda() {
        var invocations = new AtomicInteger();
        var stream = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> {
            invocations.incrementAndGet();
            return CompletableFuture.completedFuture(Map.of());
        });

        ImmutableMap<Instant, TestValue> result = stream.readRange(SLOT_APR_3, SLOT_APR_1).join();

        assertThat(result).isEmpty();
        assertThat(invocations.get()).isZero();
    }

    @Test
    void isCached_alwaysFalse_sinceNothingIsRetained() {
        var stream = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                values.put(slot, Optional.of(new TestValue("v-" + slot)));
            }
            return CompletableFuture.completedFuture(values);
        });
        // Even after a read computed the slot, the no-op cache retains nothing, so isCached stays false.
        stream.readRange(SLOT_APR_1, SLOT_APR_1).join();

        assertThat(stream.isCached(SLOT_APR_1).join()).isFalse();
    }

    @Test
    void defineStream_sameKey_returnsSameHandle() {
        var first = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE,
                                       _ -> CompletableFuture.completedFuture(Map.of()));
        var second = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE,
                                        _ -> CompletableFuture.completedFuture(Map.of()));

        assertThat(second).isSameAs(first);
    }

    @Test
    void defineStream_conflictingResolution_throws() {
        cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> CompletableFuture.completedFuture(Map.of()));

        assertThatThrownBy(() -> cache.defineStream(STREAM_ID, SCOPE, Resolution.halfHourly(), TYPE,
                                                    _ -> CompletableFuture.completedFuture(Map.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteAll_returnZero_sinceNothingIsRetained() {
        cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> CompletableFuture.completedFuture(Map.of()));

        assertThat(cache.deleteAllForScope(SCOPE).join()).isZero();
        assertThat(cache.deleteAllForStream(STREAM_ID).join()).isZero();
        assertThat(cache.deleteOlderThan(SLOT_APR_3).join()).isZero();
    }

    public record TestValue(String content) {}
}
