package net.yudichev.jiotty.analyticscache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.analyticscache.TimeSeriesCache.Scope;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeSeriesCacheImplTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Instant SLOT_APR_1 = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant SLOT_APR_2 = Instant.parse("2026-04-02T00:00:00Z");
    private static final Instant SLOT_APR_3 = Instant.parse("2026-04-03T00:00:00Z");
    private static final Instant SLOT_APR_5 = Instant.parse("2026-04-05T00:00:00Z");
    private static final TypeToken<TestRow> ROW_TYPE = TypeToken.of(TestRow.class);
    private static final String STREAM_A = "stream-a";
    private static final String STREAM_B = "stream-b";

    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();

    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl domainService;
    private DataSourceFactory dataSourceFactory;
    private ProgrammableClock clock;
    private SmileCodec smileCodec;
    private JsonUtf8Codec jsonCodec;
    private TimeSeriesCacheImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(T0);
        executor = new SingleThreadedSchedulingExecutor("time-series-cache-test");
        dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, () -> executor);
        domainService.start();
        smileCodec = new SmileCodec();
        jsonCodec = new JsonUtf8Codec();
        service = newCache(new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec)));
        service.start();
    }

    private TimeSeriesCacheImpl newCache(CodecRegistry codecRegistry) {
        return new TimeSeriesCacheImpl(dataSourceFactory,
                                       () -> executor,
                                       domainService,
                                       clock,
                                       codecRegistry,
                                       1,
                                       TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME,
                                       PersistenceDomainMigrator.FAIL_ON_MIGRATION);
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(service == null ? null : service::stop,
                                 domainService == null ? null : domainService::stop,
                                 executor);
    }

    /// Wires a stream that fetches missing slots from a fixed `seedRows` map, recording the slot sets handed to the lambda for assertion.
    private TimeSeriesStream<TestRow> defineStreamWithSeed(String streamId, Scope scope, Resolution resolution, Map<Instant, TestRow> seedRows) {
        return service.defineStream(streamId, scope, resolution, ROW_TYPE, missingSlots -> {
            var out = new HashMap<Instant, TestRow>();
            for (Instant slot : missingSlots) {
                TestRow row = seedRows.get(slot);
                if (row != null) {
                    out.put(slot, row);
                }
            }
            return CompletableFuture.completedFuture(out);
        });
    }

    private static ImmutableMap<Instant, TestRow> compose(TimeSeriesStream<TestRow> stream, Instant from, Instant to) {
        return stream.readRange(from, to).orTimeout(5, SECONDS).join();
    }

    @Test
    void emptyCache_readRange_invokesLambdaForEverySlot() {
        var seed = Map.of(SLOT_APR_1, new TestRow("2026-04-01", 1, "a"),
                          SLOT_APR_2, new TestRow("2026-04-02", 2, "b"),
                          SLOT_APR_3, new TestRow("2026-04-03", 3, "c"));
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), seed);

        var result = compose(stream, SLOT_APR_1, SLOT_APR_3);

        assertThat(result).containsOnlyKeys(SLOT_APR_1, SLOT_APR_2, SLOT_APR_3)
                          .containsAllEntriesOf(seed);
    }

    @Test
    void readRange_writesBackComputedSlots() {
        var seed = new HashMap<Instant, TestRow>();
        seed.put(SLOT_APR_1, new TestRow("2026-04-01", 1, "a"));
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), seed);

        compose(stream, SLOT_APR_1, SLOT_APR_1);
        // Mutate seed so a re-compose with no cache would see new data — but the original is already cached, so this value must NOT surface.
        seed.put(SLOT_APR_1, new TestRow("2026-04-01", 999, "mutated"));

        var result = compose(stream, SLOT_APR_1, SLOT_APR_1);
        assertThat(result).containsEntry(SLOT_APR_1, new TestRow("2026-04-01", 1, "a"));
    }

    @Test
    void readRange_acrossScopes_isolatesByScope() {
        var stream1 = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                           Map.of(SLOT_APR_1, new TestRow("d", 11, "u1")));
        var stream2 = defineStreamWithSeed(STREAM_A, Scope.user("user-2"), Resolution.daily(),
                                           Map.of(SLOT_APR_1, new TestRow("d", 22, "u2")));
        var streamG = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                           Map.of(SLOT_APR_1, new TestRow("d", 33, "g")));
        var streamRA = defineStreamWithSeed(STREAM_A, Scope.region("A"), Resolution.daily(),
                                            Map.of(SLOT_APR_1, new TestRow("d", 44, "rA")));
        var streamRB = defineStreamWithSeed(STREAM_A, Scope.region("B"), Resolution.daily(),
                                            Map.of(SLOT_APR_1, new TestRow("d", 55, "rB")));

        assertThat(compose(stream1, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 11, "u1"));
        assertThat(compose(stream2, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 22, "u2"));
        assertThat(compose(streamG, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 33, "g"));
        assertThat(compose(streamRA, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 44, "rA"));
        assertThat(compose(streamRB, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 55, "rB"));
    }

    @Test
    void regionScope_rejectsBlankCode() {
        assertThatThrownBy(() -> Scope.region("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Scope.region("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userScope_rejectsBlankUserId() {
        assertThatThrownBy(() -> Scope.user("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defineStream_blankStreamId_throws() {
        assertThatThrownBy(() -> defineStreamWithSeed("", Scope.global(), Resolution.daily(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defineStream_sameParams_isIdempotent_andReturnsSameHandle() {
        var first = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of());
        var second = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of());

        assertThat(second).isSameAs(first);
    }

    @Test
    void defineStream_conflictingResolution_throws() {
        defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of());
        assertThatThrownBy(() -> defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.halfHourly(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting redefinition");
    }

    @Test
    void defineStream_conflictingType_throws() {
        defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of());
        Function<SortedSet<Instant>, CompletableFuture<Map<Instant, String>>> empty =
                _ -> CompletableFuture.completedFuture(Map.of());
        assertThatThrownBy(() -> service.defineStream(STREAM_A, Scope.user("user-1"), Resolution.daily(), TypeToken.of(String.class), empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting redefinition");
    }

    @Test
    void defineStream_sameStreamIdDifferentScope_isDistinctRegistration() {
        var u1 = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 1, "u1")));
        var u2 = defineStreamWithSeed(STREAM_A, Scope.user("user-2"), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 2, "u2")));

        assertThat(u2).isNotSameAs(u1);
        assertThat(compose(u1, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 1, "u1"));
        assertThat(compose(u2, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 2, "u2"));
    }

    @Test
    void deleteAllForScope_user_removesOnlyThatUsersRows_andEvictsRegistry() {
        var u1A = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                       Map.of(SLOT_APR_1, new TestRow("d", 1, "u1-a")));
        var u1B = defineStreamWithSeed(STREAM_B, Scope.user("user-1"), Resolution.daily(),
                                       Map.of(SLOT_APR_2, new TestRow("d", 2, "u1-b")));
        var u2 = defineStreamWithSeed(STREAM_A, Scope.user("user-2"), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 3, "u2")));
        var g = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                     Map.of(SLOT_APR_1, new TestRow("d", 4, "g")));
        // Warm rows in all streams.
        compose(u1A, SLOT_APR_1, SLOT_APR_1);
        compose(u1B, SLOT_APR_2, SLOT_APR_2);
        compose(u2, SLOT_APR_1, SLOT_APR_1);
        compose(g, SLOT_APR_1, SLOT_APR_1);

        int deleted = service.deleteAllForScope(Scope.user("user-1")).orTimeout(5, SECONDS).join();

        assertThat(deleted).isEqualTo(2);
        // Other scopes still hit.
        assertThat(compose(u2, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 3, "u2"));
        assertThat(compose(g, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 4, "g"));
        // Re-registering user-1's stream now returns a fresh handle, proving registry eviction.
        var u1AFresh = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                            Map.of(SLOT_APR_1, new TestRow("d", 9, "u1-a-fresh")));
        assertThat(u1AFresh).isNotSameAs(u1A);
    }

    @Test
    void deleteAllForScope_region_removesOnlyThatRegionsRows() {
        var rA = defineStreamWithSeed(STREAM_A, Scope.region("A"), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 1, "rA-a")));
        var rB = defineStreamWithSeed(STREAM_A, Scope.region("B"), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 2, "rB")));
        var g = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                     Map.of(SLOT_APR_1, new TestRow("d", 3, "g")));
        compose(rA, SLOT_APR_1, SLOT_APR_1);
        compose(rB, SLOT_APR_1, SLOT_APR_1);
        compose(g, SLOT_APR_1, SLOT_APR_1);

        int deleted = service.deleteAllForScope(Scope.region("A")).orTimeout(5, SECONDS).join();

        assertThat(deleted).isEqualTo(1);
        assertThat(compose(rB, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 2, "rB"));
        assertThat(compose(g, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 3, "g"));
    }

    @Test
    void deleteAllForScope_global_removesOnlyGlobalRowsAcrossStreams() {
        var gA = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 1, "gA")));
        var gB = defineStreamWithSeed(STREAM_B, Scope.global(), Resolution.daily(),
                                      Map.of(SLOT_APR_2, new TestRow("d", 2, "gB")));
        var u = defineStreamWithSeed(STREAM_A, Scope.user("u"), Resolution.daily(),
                                     Map.of(SLOT_APR_1, new TestRow("d", 3, "u")));
        compose(gA, SLOT_APR_1, SLOT_APR_1);
        compose(gB, SLOT_APR_2, SLOT_APR_2);
        compose(u, SLOT_APR_1, SLOT_APR_1);

        int deleted = service.deleteAllForScope(Scope.global()).orTimeout(5, SECONDS).join();

        assertThat(deleted).isEqualTo(2);
        assertThat(compose(u, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 3, "u"));
    }

    @Test
    void deleteAllForScope_nullScope_throws() {
        assertThatThrownBy(() -> service.deleteAllForScope(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void deleteAllForStream_removesAllScopesForThatStream_andEvictsRegistry() {
        var u1A = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                       Map.of(SLOT_APR_1, new TestRow("d", 1, "u1")));
        var u2A = defineStreamWithSeed(STREAM_A, Scope.user("user-2"), Resolution.daily(),
                                       Map.of(SLOT_APR_1, new TestRow("d", 2, "u2")));
        var gA = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                      Map.of(SLOT_APR_1, new TestRow("d", 3, "g")));
        var u1B = defineStreamWithSeed(STREAM_B, Scope.user("user-1"), Resolution.daily(),
                                       Map.of(SLOT_APR_1, new TestRow("d", 4, "u1-b")));
        compose(u1A, SLOT_APR_1, SLOT_APR_1);
        compose(u2A, SLOT_APR_1, SLOT_APR_1);
        compose(gA, SLOT_APR_1, SLOT_APR_1);
        compose(u1B, SLOT_APR_1, SLOT_APR_1);

        int deleted = service.deleteAllForStream(STREAM_A).orTimeout(5, SECONDS).join();

        assertThat(deleted).isEqualTo(3);
        // Stream-B for user-1 still hits.
        assertThat(compose(u1B, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("d", 4, "u1-b"));
        // Re-registering any of the evicted (STREAM_A, scope) tuples returns a fresh handle.
        var u1AFresh = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of());
        assertThat(u1AFresh).isNotSameAs(u1A);
    }

    @Test
    void globalScope_isStoredWithScopeKindGlobalNotUser() throws Exception {
        var g = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(),
                                     Map.of(SLOT_APR_1, new TestRow("d", 1, "g")));
        var u = defineStreamWithSeed(STREAM_A, Scope.user("u"), Resolution.daily(),
                                     Map.of(SLOT_APR_2, new TestRow("d", 2, "u")));
        compose(g, SLOT_APR_1, SLOT_APR_1);
        compose(u, SLOT_APR_2, SLOT_APR_2);

        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT scope_kind, scope_value FROM " + TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME + "_entry " +
                     "WHERE stream_id=? AND slot_start=?")) {
            stmt.setString(1, STREAM_A);
            stmt.setObject(2, SLOT_APR_1.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getShort(1)).isEqualTo((short) 0); // SCOPE_KIND_GLOBAL — distinct discriminator from User (1)
                assertThat(rs.getString(2)).isNull();
            }
            stmt.setObject(2, SLOT_APR_2.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getShort(1)).isEqualTo((short) 1); // SCOPE_KIND_USER
                assertThat(rs.getString(2)).isEqualTo("u");
            }
        }
    }

    @Test
    void writtenRow_isStoredAsByteaFramedWithMagicAndSmileFormatId() throws Exception {
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                          Map.of(SLOT_APR_1, new TestRow("2026-04-01", 7, "payload")));
        compose(stream, SLOT_APR_1, SLOT_APR_1);

        byte[] rowBytes;
        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "SELECT value FROM " + TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME + "_entry " +
                     "WHERE scope_kind=1 AND scope_value IS NOT DISTINCT FROM 'user-1' " +
                     "AND stream_id=? AND slot_start=?")) {
            stmt.setString(1, STREAM_A);
            stmt.setObject(2, SLOT_APR_1.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                rowBytes = rs.getBytes(1);
            }
        }
        assertThat(rowBytes).isNotNull();
        assertThat(rowBytes.length).isGreaterThan(2);
        assertThat(rowBytes[0]).isEqualTo(CodecRegistry.MAGIC);
        assertThat(rowBytes[1]).isEqualTo(CodecRegistry.FMT_SMILE);
    }

    @Test
    void readRange_offGrainFromForDailyResolution_throws() {
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("u"), Resolution.daily(), Map.of());
        var offGrainSlot = SLOT_APR_1.plus(Duration.ofHours(3));
        assertThatThrownBy(() -> stream.readRange(offGrainSlot, SLOT_APR_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not aligned");
    }

    @Test
    void readRange_offGrainToForDailyResolution_throws() {
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("u"), Resolution.daily(), Map.of());
        var offGrainSlot = SLOT_APR_5.plus(Duration.ofMinutes(30));
        assertThatThrownBy(() -> stream.readRange(SLOT_APR_1, offGrainSlot))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not aligned");
    }

    @Test
    void readRange_subSecondFrom_throws() {
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("u"), Resolution.daily(), Map.of());
        var slotWithNanos = SLOT_APR_1.plusNanos(1);
        assertThatThrownBy(() -> stream.readRange(slotWithNanos, SLOT_APR_5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not aligned");
    }

    @Test
    void readRange_fromAfterTo_throws() {
        var stream = defineStreamWithSeed(STREAM_A, Scope.global(), Resolution.daily(), Map.of());
        assertThatThrownBy(() -> stream.readRange(SLOT_APR_5, SLOT_APR_1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void halfHourlyResolution_acceptsAlignedHalfHourSlotsAndRejectsOffGrain() {
        var slot1230 = Instant.parse("2026-04-15T12:30:00Z");
        var slot1300 = Instant.parse("2026-04-15T13:00:00Z");
        var slot1245 = Instant.parse("2026-04-15T12:45:00Z");

        var stream = defineStreamWithSeed("half-hour-stream", Scope.user("u"), Resolution.halfHourly(),
                                          Map.of(slot1230, new TestRow("12:30", 1, "a"),
                                                 slot1300, new TestRow("13:00", 2, "b")));

        assertThat(compose(stream, slot1230, slot1300)).containsOnlyKeys(slot1230, slot1300);
        assertThatThrownBy(() -> stream.readRange(slot1245, slot1300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not aligned");
    }

    @Test
    void readingJsonEncodedRow_stillRoundTripsViaSharedReadRegistry() {
        // A second cache instance with JSON-UTF-8 as the write codec writes into the same domain; the original Smile-writing service then reads it via the
        //  shared read registry. Demonstrates that wire-format migration is forwards-and-backwards compatible per the codec abstraction.
        var jsonWriter = newCache(new CodecRegistry(jsonCodec, ImmutableList.of(jsonCodec, smileCodec)));
        jsonWriter.start();
        try {
            var stream = jsonWriter.defineStream(STREAM_A, Scope.user("user-1"), Resolution.daily(), ROW_TYPE,
                                                 missingSlots -> {
                                                     var out = new HashMap<Instant, TestRow>();
                                                     for (Instant slot : missingSlots) {
                                                         out.put(slot, new TestRow("2026-04-02", 9, "json"));
                                                     }
                                                     return CompletableFuture.completedFuture(out);
                                                 });
            stream.readRange(SLOT_APR_2, SLOT_APR_2).orTimeout(5, SECONDS).join();
        } finally {
            jsonWriter.stop();
        }

        // The Smile-write/JSON-and-Smile-read service in setUp() decodes the JSON row. Define a stream whose lambda would return DIFFERENT data, to prove
        // readRange returned the cached JSON-encoded row rather than re-invoking the lambda.
        var readStream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                              Map.of(SLOT_APR_2, new TestRow("would-be-fresh", 0, "lambda")));
        assertThat(compose(readStream, SLOT_APR_2, SLOT_APR_2))
                .containsEntry(SLOT_APR_2, new TestRow("2026-04-02", 9, "json"));
    }

    public record TestRow(String date, int n, String label) {}
}
