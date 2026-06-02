package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import jakarta.inject.Provider;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class TimeSeriesStreamTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Instant SLOT_APR_1 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant SLOT_APR_2 = Instant.parse("2026-04-02T00:00:00Z");
    private static final Instant SLOT_APR_3 = Instant.parse("2026-04-03T00:00:00Z");
    private static final Instant SLOT_APR_4 = Instant.parse("2026-04-04T00:00:00Z");
    private static final Instant SLOT_APR_5 = Instant.parse("2026-04-05T00:00:00Z");
    private static final Scope SCOPE = Scope.user("u");
    private static final String STREAM_ID = "test-stream";
    private static final TypeToken<TestValue> TYPE = TypeToken.of(TestValue.class);

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private final AtomicInteger streamIdSuffix = new AtomicInteger();
    private final TestAdminAlertService adminAlertService = new TestAdminAlertService();
    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private DataSourceFactory dataSourceFactory;
    private ProgrammableClock clock;
    private TimeSeriesCacheImpl cache;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(T0);
        executor = new SingleThreadedSchedulingExecutor("time-series-stream-test");
        dataSourceFactory = postgres.dataSourceFactory();
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        domainService.start();
        cache = newCache();
        cache.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(cache == null ? null : cache::stop,
                                 domainService == null ? null : domainService::stop,
                                 executor);
    }

    /// Each test gets a unique streamId so warmed rows from one test don't surface as cache hits in another sharing the same `domain_entry` table across the
    /// embedded-postgres lifetime.
    private String uniqueStreamId() {
        return STREAM_ID + "-" + streamIdSuffix.incrementAndGet();
    }

    private TimeSeriesStream<TestValue> defineStream(Resolution resolution,
                                                     Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<TestValue>>>> slotsComputation) {
        return cache.defineStream(uniqueStreamId(), SCOPE, resolution, TYPE, slotsComputation);
    }

    @Test
    void halfHourlyResolution_passesHalfHourAlignedSlotsToLambda() {
        // Wires the stream at half-hour grain — readRange must derive the missing-slot set by stepping 30 minutes, not days. Proves the stream is
        // resolution-driven (no hardcoded ofDays(1)).
        var from = Instant.parse("2026-04-15T12:00:00Z");
        var to = Instant.parse("2026-04-15T14:00:00Z");
        var expectedSlots = List.of(
                Instant.parse("2026-04-15T12:00:00Z"),
                Instant.parse("2026-04-15T12:30:00Z"),
                Instant.parse("2026-04-15T13:00:00Z"),
                Instant.parse("2026-04-15T13:30:00Z"),
                Instant.parse("2026-04-15T14:00:00Z"));

        var receivedSets = new ArrayList<SortedSet<Instant>>();
        var stream = defineStream(Resolution.halfHourly(), missingSlots -> {
            receivedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                values.put(slot, Optional.of(new TestValue(slot.toString())));
            }
            return CompletableFuture.completedFuture(values);
        });

        Map<Instant, TestValue> result = stream.readRange(from, to).orTimeout(5, SECONDS).join();

        assertThat(receivedSets.getFirst()).containsExactlyElementsOf(expectedSlots);
        assertThat(result).containsOnlyKeys(expectedSlots.toArray(new Instant[0]));
    }

    @Test
    void coldCache_invokesLambdaOnceWithAllMissingSlots() {
        var chunkInvocations = new AtomicInteger();
        var receivedSets = new ArrayList<SortedSet<Instant>>();
        var stream = defineStream(Resolution.daily(), missingSlots -> {
            chunkInvocations.incrementAndGet();
            receivedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                values.put(slot, Optional.of(new TestValue("chunked-" + slot)));
            }
            return CompletableFuture.completedFuture(values);
        });

        Map<Instant, TestValue> result = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();

        assertThat(chunkInvocations.get()).isEqualTo(1);
        assertThat(receivedSets).hasSize(1);
        assertThat(receivedSets.getFirst()).containsExactly(SLOT_APR_1, SLOT_APR_2, SLOT_APR_3, SLOT_APR_4, SLOT_APR_5);
        assertThat(result).containsEntry(SLOT_APR_1, new TestValue("chunked-" + SLOT_APR_1))
                          .containsEntry(SLOT_APR_5, new TestValue("chunked-" + SLOT_APR_5))
                          .hasSize(5);
    }

    @Test
    void partialCacheHit_passesOnlyMissingSlotsToLambda() {
        // The lambda fills only slots 2 and 4; everything else is omitted from the result map so it stays uncached. First compose over [2, 4] seeds rows for
        // 2 and 4 (and leaves 3 uncached). Second compose over the wider [1, 5] must therefore invoke the lambda for exactly {1, 3, 5}.
        var recordedSets = new ArrayList<SortedSet<Instant>>();
        var stream = cache.defineStream(uniqueStreamId(), SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            recordedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                if (slot.equals(SLOT_APR_2) || slot.equals(SLOT_APR_4)) {
                    values.put(slot, Optional.of(new TestValue("warm-" + slot)));
                }
            }
            return CompletableFuture.completedFuture(values);
        });
        stream.readRange(SLOT_APR_2, SLOT_APR_4).orTimeout(5, SECONDS).join();
        recordedSets.clear();

        Map<Instant, TestValue> result = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();

        assertThat(recordedSets).hasSize(1);
        assertThat(recordedSets.getFirst()).containsExactly(SLOT_APR_1, SLOT_APR_3, SLOT_APR_5);
        assertThat(result).containsOnlyKeys(SLOT_APR_2, SLOT_APR_4)
                          .containsEntry(SLOT_APR_2, new TestValue("warm-" + SLOT_APR_2))
                          .containsEntry(SLOT_APR_4, new TestValue("warm-" + SLOT_APR_4));
    }

    @Test
    void allCached_doesNotInvokeLambda() {
        var invocations = new AtomicInteger();
        var streamId = uniqueStreamId();
        var stream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            invocations.incrementAndGet();
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                values.put(slot, Optional.of(new TestValue("c-" + slot)));
            }
            return CompletableFuture.completedFuture(values);
        });
        // Warm all 5 slots.
        stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(invocations.get()).isEqualTo(1);
        invocations.set(0);

        // Re-compose: every slot must be a cache hit, lambda must not be invoked.
        Map<Instant, TestValue> result = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();

        assertThat(invocations.get()).isZero();
        assertThat(result).hasSize(5);
    }

    @Test
    void omittedKeysAreTreatedAsNoValue_andNotCached() {
        // Lambda returns a map that contains only some of the missing slots; the omitted ones must be treated as having no value (no write, omitted from
        // the composed output). Omission is distinct from a tombstone (Optional.empty): an omitted slot stays uncached and is re-queried.
        var receivedSets = new ArrayList<SortedSet<Instant>>();
        var streamId = uniqueStreamId();
        var stream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            receivedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                if (slot.equals(SLOT_APR_2) || slot.equals(SLOT_APR_4)) {
                    values.put(slot, Optional.of(new TestValue("filled-" + slot)));
                }
                // Omit SLOT_APR_1, SLOT_APR_3, SLOT_APR_5 entirely.
            }
            return CompletableFuture.completedFuture(values);
        });

        Map<Instant, TestValue> firstResult = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(firstResult).containsOnlyKeys(SLOT_APR_2, SLOT_APR_4);

        // Second compose: omitted slots must NOT have been cached, so the lambda is asked for them again.
        receivedSets.clear();
        stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(receivedSets.getFirst()).containsExactly(SLOT_APR_1, SLOT_APR_3, SLOT_APR_5);
    }

    @Test
    void emptyOptionalIsTombstoned_andNotRecomputed() {
        // A slot the lambda resolves to Optional.empty() is a negative-cache tombstone: it is excluded from the composed output (no value), but unlike an
        // omitted slot it IS cached, so a later compose does not re-query it. Slots 2 and 4 get values; slots 1, 3, 5 get tombstones.
        var receivedSets = new ArrayList<SortedSet<Instant>>();
        var streamId = uniqueStreamId();
        var stream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            receivedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                if (slot.equals(SLOT_APR_2) || slot.equals(SLOT_APR_4)) {
                    values.put(slot, Optional.of(new TestValue("v-" + slot)));
                } else {
                    values.put(slot, Optional.empty());
                }
            }
            return CompletableFuture.completedFuture(values);
        });

        Map<Instant, TestValue> firstResult = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(firstResult).containsOnlyKeys(SLOT_APR_2, SLOT_APR_4);

        // Second compose over the same range: every slot is now a hit (values + tombstones), so the lambda is not invoked at all.
        receivedSets.clear();
        Map<Instant, TestValue> secondResult = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(receivedSets).isEmpty();
        assertThat(secondResult).containsOnlyKeys(SLOT_APR_2, SLOT_APR_4)
                                .containsEntry(SLOT_APR_2, new TestValue("v-" + SLOT_APR_2))
                                .containsEntry(SLOT_APR_4, new TestValue("v-" + SLOT_APR_4));
    }

    @Test
    void singleSlotFilledOthersOmitted_onlyTheFilledSlotIsCached() {
        // Sparse-fill variant of omittedKeysAreTreatedAsNoValue_andNotCached: only one slot in the requested range gets a value, the others are omitted
        // from the lambda's returned map. None of the omitted slots are cached, so a re-compose asks the lambda for them again.
        var receivedSets = new ArrayList<SortedSet<Instant>>();
        var streamId = uniqueStreamId();
        var stream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            receivedSets.add(missingSlots);
            var values = new HashMap<Instant, Optional<TestValue>>();
            for (Instant slot : missingSlots) {
                if (slot.equals(SLOT_APR_3)) {
                    values.put(slot, Optional.of(new TestValue("only-3")));
                }
            }
            return CompletableFuture.completedFuture(values);
        });

        Map<Instant, TestValue> firstResult = stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(firstResult).containsOnlyKeys(SLOT_APR_3);

        // Omitted slots were not cached: a second compose still sees the other four slots as missing.
        receivedSets.clear();
        stream.readRange(SLOT_APR_1, SLOT_APR_5).orTimeout(5, SECONDS).join();
        assertThat(receivedSets.getFirst()).containsExactly(SLOT_APR_1, SLOT_APR_2, SLOT_APR_4, SLOT_APR_5);
    }

    @Test
    void entriesOutsideMissingSetAreIgnored() {
        var streamId = uniqueStreamId();
        var firstCallSeen = new AtomicBoolean(false);
        var stream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE, missingSlots -> {
            var values = new HashMap<Instant, Optional<TestValue>>();
            if (firstCallSeen.getAndSet(true)) {
                // Second call: fill all missing slots with "fresh-" values, plus an over-fetched entry for slot 2 (already cached, NOT in `missing`).
                for (Instant slot : missingSlots) {
                    values.put(slot, Optional.of(new TestValue("fresh-" + slot)));
                }
                values.put(SLOT_APR_2, Optional.of(new TestValue("over-fetched-overwrite-attempt")));
            } else {
                // First call: warm slot 2 only.
                values.put(SLOT_APR_2, Optional.of(new TestValue("cached-2")));
            }
            return CompletableFuture.completedFuture(values);
        });

        // First compose: seeds SLOT_APR_2.
        stream.readRange(SLOT_APR_2, SLOT_APR_2).orTimeout(5, SECONDS).join();
        // Second compose: lambda returns an over-fetched value for slot 2 — the composer must ignore it.
        Map<Instant, TestValue> result = stream.readRange(SLOT_APR_1, SLOT_APR_3).orTimeout(5, SECONDS).join();

        assertThat(result).containsEntry(SLOT_APR_2, new TestValue("cached-2")); // not overwritten

        // And the cache itself still holds the original value, not the over-fetched one — a fresh compose against the same range with a stream that would
        // re-fetch fresh data must still see the cached value at slot 2.
        var verifyStream = cache.defineStream(streamId, SCOPE, Resolution.daily(), TYPE,
                                              _ -> CompletableFuture.completedFuture(Map.of()));
        assertThat(verifyStream).isSameAs(stream); // same key → idempotent registration
        var verifyResult = verifyStream.readRange(SLOT_APR_2, SLOT_APR_2).orTimeout(5, SECONDS).join();
        assertThat(verifyResult).containsEntry(SLOT_APR_2, new TestValue("cached-2"));
    }

    private TimeSeriesCacheImpl newCache() {
        var smileCodec = new SmileCodec();
        var jsonCodec = new JsonUtf8Codec();
        var codecRegistry = new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec));
        return new TimeSeriesCacheImpl(dataSourceFactory,
                                       () -> executor,
                                       domainService,
                                       clock,
                                       codecRegistry,
                                       adminAlertService,
                                       1,
                                       TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME,
                                       PersistenceDomainMigrator.FAIL_ON_MIGRATION);
    }

    public record TestValue(String content) {}
}
