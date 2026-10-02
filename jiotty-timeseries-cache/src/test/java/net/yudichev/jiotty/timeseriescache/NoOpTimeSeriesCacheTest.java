package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
    void defineStream_sameKey_eachHandleComputesWithItsOwnComputation() {
        TimeSeriesStream<TestValue> first = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> completeWithValueAtSlotApr1("first"));
        TimeSeriesStream<TestValue> second = cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> completeWithValueAtSlotApr1("second"));

        assertThat(first.readRange(SLOT_APR_1, SLOT_APR_1).getNow(null)).containsExactly(Map.entry(SLOT_APR_1, new TestValue("first")));
        assertThat(second.readRange(SLOT_APR_1, SLOT_APR_1).getNow(null)).containsExactly(Map.entry(SLOT_APR_1, new TestValue("second")));
    }

    @ParameterizedTest
    @MethodSource
    void defineStream_conflictingRedefinition_throws(Resolution resolution, TypeToken<?> type) {
        defineEmptyStream(Resolution.daily(), TYPE);

        assertThatThrownBy(() -> defineEmptyStream(resolution, type)).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> defineStream_conflictingRedefinition_throws() {
        return Stream.of(arguments(Resolution.halfHourly(), TYPE),
                         arguments(Resolution.daily(), TypeToken.of(String.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void deleteAll_forgetsTheDeletedStreamsDefinitions(boolean byScope) {
        defineEmptyStream(Resolution.daily(), TYPE);

        assertThat(byScope ? cache.deleteAllForScope(SCOPE) : cache.deleteAllForStream(STREAM_ID)).succeedsWithin(Duration.ZERO);

        assertThatCode(() -> defineEmptyStream(Resolution.halfHourly(), TYPE)).doesNotThrowAnyException();
    }

    @Test
    void deleteAll_returnZero_sinceNothingIsRetained() {
        cache.defineStream(STREAM_ID, SCOPE, Resolution.daily(), TYPE, _ -> CompletableFuture.completedFuture(Map.of()));

        assertThat(cache.deleteAllForScope(SCOPE).join()).isZero();
        assertThat(cache.deleteAllForStream(STREAM_ID).join()).isZero();
        assertThat(cache.deleteOlderThan(SLOT_APR_3).join()).isZero();
    }

    private static CompletableFuture<Map<Instant, Optional<TestValue>>> completeWithValueAtSlotApr1(String content) {
        return CompletableFuture.completedFuture(Map.of(SLOT_APR_1, Optional.of(new TestValue(content))));
    }

    private <T> void defineEmptyStream(Resolution resolution, TypeToken<T> type) {
        cache.defineStream(STREAM_ID, SCOPE, resolution, type, 1, _ -> CompletableFuture.completedFuture(Map.of()));
    }

    @CacheSchemaVersion(1)
    public record TestValue(String content) {}
}
