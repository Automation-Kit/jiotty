package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import net.yudichev.jiotty.persistence.test.UsingEmbeddedPostgres;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@UsingEmbeddedPostgres
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
    private TestAdminAlertService adminAlertService;
    private TimeSeriesCacheImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(T0);
        executor = new SingleThreadedSchedulingExecutor("time-series-cache-test");
        dataSourceFactory = postgres.dataSourceFactory();
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, () -> executor, new ListenerBackedTaskExceptionHandlerRegistry());
        domainService.start();
        smileCodec = new SmileCodec();
        jsonCodec = new JsonUtf8Codec();
        adminAlertService = new TestAdminAlertService(clock);
        service = newCache(new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec)));
        service.start();
    }

    private TimeSeriesCacheImpl newCache(CodecRegistry codecRegistry) {
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

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(service == null ? null : service::stop,
                                 domainService == null ? null : domainService::stop,
                                 executor);
    }

    /// Wires a stream that fetches missing slots from a fixed `seedRows` map, recording the slot sets handed to the lambda for assertion.
    private TimeSeriesStream<TestRow> defineStreamWithSeed(String streamId, Scope scope, Resolution resolution, Map<Instant, TestRow> seedRows) {
        return service.defineStream(streamId, scope, resolution, ROW_TYPE, missingSlots -> {
            var out = new HashMap<Instant, Optional<TestRow>>();
            for (Instant slot : missingSlots) {
                TestRow row = seedRows.get(slot);
                if (row != null) {
                    out.put(slot, Optional.of(row));
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
    void emptyOptional_isPersistedAsTombstone_andNotRecomputed() {
        // A slot resolved to Optional.empty() is written as a tombstone row. A re-compose (even via a freshly-registered stream whose lambda would now return
        // a value) must read it back as a known-empty hit — proving the tombstone frame round-trips through Postgres and suppresses recomputation.
        var firstCallSlots = new AtomicInteger();
        var tombstoning = service.defineStream(STREAM_A, Scope.user("user-1"), Resolution.daily(), ROW_TYPE, missingSlots -> {
            firstCallSlots.addAndGet(missingSlots.size());
            var out = new HashMap<Instant, Optional<TestRow>>();
            missingSlots.forEach(slot -> out.put(slot, Optional.empty()));
            return CompletableFuture.completedFuture(out);
        });
        assertThat(compose(tombstoning, SLOT_APR_1, SLOT_APR_3)).isEmpty();
        assertThat(firstCallSlots.get()).isEqualTo(3);

        // Fresh stream registration for the same key after eviction; its lambda would return a value, but every slot is tombstoned so it is never asked.
        service.deleteAllForStream(STREAM_A).orTimeout(5, SECONDS).join();
        var reseed = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                          Map.of(SLOT_APR_2, new TestRow("2026-04-02", 2, "fresh")));
        // Cache was cleared, so the fresh lambda runs again and its value for SLOT_APR_2 surfaces — confirms deleteAllForStream evicted the tombstone rows too.
        assertThat(compose(reseed, SLOT_APR_1, SLOT_APR_3)).containsEntry(SLOT_APR_2, new TestRow("2026-04-02", 2, "fresh"));
    }

    @Test
    void rowWithStaleSchemaVersion_isWipedAndRecomputed_withoutAlerting() throws Exception {
        // A row written under an older schema version (here forced to 2 while the stream's type resolves to version 1) is a routine post-deploy mismatch: it
        // is discarded so the slot recomputes, the bad row is wiped, and NO admin alert fires (a version bump is expected, not corruption).
        var staleFrame = new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec))
                .encode(Optional.of(new TestRow("2026-04-01", 1, "stale")), 2);
        insertRawRow(Scope.user("user-1"), STREAM_A, SLOT_APR_1, staleFrame);

        var stream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                          Map.of(SLOT_APR_1, new TestRow("2026-04-01", 1, "recomputed")));
        assertThat(compose(stream, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("2026-04-01", 1, "recomputed"));
        assertThat(adminAlertService.alertsById()).isEmpty();

        // The wiped slot was rewritten at the current version: a re-read returns the recomputed value without the stale lambda being needed.
        var reread = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                          Map.of(SLOT_APR_1, new TestRow("would-not-be-asked", 0, "lambda")));
        assertThat(compose(reread, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("2026-04-01", 1, "recomputed"));
    }

    /// Each case is `(scope, streamId, expectedFamily, expectedScopeKind, mustNotLeak)`: a corrupt row across each scope kind, including a consumption-style
    /// streamId whose variant suffix embeds an MPAN/serial — the alert must carry only the family prefix and the scope kind, never the suffix or scope value.
    static Stream<Arguments> corruptRow_isWipedAndRecomputed_andRaisesOnePiiFreeErrorAlert() {
        return Stream.of(
                arguments(Scope.user("user-1"), "stream-a", "stream-a", "user", "user-1"),
                arguments(Scope.region("A"), "octopus.consumption:9999999999999:99XXX99999", "octopus.consumption", "region", "9999999999999"),
                arguments(Scope.global(), "savings:secret-hash", "savings", "global", "secret-hash"));
    }

    @ParameterizedTest
    @MethodSource
    void corruptRow_isWipedAndRecomputed_andRaisesOnePiiFreeErrorAlert(Scope scope,
                                                                       String streamId,
                                                                       String expectedFamily,
                                                                       String expectedScopeKind,
                                                                       String mustNotLeak) throws Exception {
        // A validly-framed row at the current version whose payload cannot be decoded (here a String payload read as a TestRow) is genuine corruption: it is
        // discarded + wiped + recomputed, and raises exactly one ERROR alert carrying only the stream family + scope kind (never the scope value or row data).
        var corruptFrame = new CodecRegistry(smileCodec, ImmutableList.of(smileCodec, jsonCodec))
                .encode(Optional.of("not a TestRow"), 1);
        insertRawRow(scope, streamId, SLOT_APR_1, corruptFrame);

        var stream = defineStreamWithSeed(streamId, scope, Resolution.daily(),
                                          Map.of(SLOT_APR_1, new TestRow("2026-04-01", 1, "recomputed")));
        assertThat(compose(stream, SLOT_APR_1, SLOT_APR_1)).containsEntry(SLOT_APR_1, new TestRow("2026-04-01", 1, "recomputed"));

        assertThat(adminAlertService.activeAlertsById().values()).singleElement().satisfies(alert -> {
            assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.ERROR);
            assertThat(alert.labels()).containsEntry("streamFamily", expectedFamily).containsEntry("scopeKind", expectedScopeKind);
            // PII-free: never the scope value / variant suffix, never the corrupt row's payload bytes.
            String description = adminAlertService.eventsByAlertId(alert.id()).getFirst().description();
            assertThat(alert.title() + " " + description).doesNotContain(mustNotLeak).doesNotContain("not a TestRow");
        });
    }

    /// Inserts a row with an arbitrary (possibly hand-crafted / stale-version / corrupt) frame straight into the table, bypassing the codec, so a read can
    /// exercise the discard/wipe path against any scope.
    private void insertRawRow(Scope scope, String streamId, Instant slot, byte[] frame) throws Exception {
        short scopeKind = switch (scope) {
            case Scope.Global _ -> 0;
            case Scope.User _ -> 1;
            case Scope.Region _ -> 2;
        };
        String scopeValue = switch (scope) {
            case Scope.Global _ -> null;
            case Scope.User user -> user.userId();
            case Scope.Region region -> region.code();
        };
        try (Connection connection = dataSourceFactory.create().getConnection();
             PreparedStatement stmt = connection.prepareStatement(
                     "INSERT INTO " + TimeSeriesCacheSchema.DEFAULT_DOMAIN_NAME + "_entry " +
                     "(scope_kind, scope_value, stream_id, slot_start, value, created_at, updated_at) VALUES (?,?,?,?,?,?,?)")) {
            OffsetDateTime now = T0.atOffset(ZoneOffset.UTC);
            stmt.setShort(1, scopeKind);
            stmt.setString(2, scopeValue);
            stmt.setString(3, streamId);
            stmt.setObject(4, slot.atOffset(ZoneOffset.UTC));
            stmt.setBinaryStream(5, new ByteArrayInputStream(frame), frame.length);
            stmt.setObject(6, now);
            stmt.setObject(7, now);
            stmt.executeUpdate();
        }
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
        Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<String>>>> empty =
                _ -> CompletableFuture.completedFuture(Map.of());
        // String is unannotated, so use the explicit-version overload to reach the type-conflict check (the annotation overload would reject it earlier).
        assertThatThrownBy(() -> service.defineStream(STREAM_A, Scope.user("user-1"), Resolution.daily(), TypeToken.of(String.class), 1, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting redefinition");
    }

    @Test
    void defineStream_unannotatedTypeViaAnnotationOverload_throws() {
        Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<String>>>> empty =
                _ -> CompletableFuture.completedFuture(Map.of());
        assertThatThrownBy(() -> service.defineStream("unannotated", Scope.global(), Resolution.daily(), TypeToken.of(String.class), empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare a @CacheSchemaVersion");
    }

    @Test
    void defineStream_explicitVersionOutOfRange_throws() {
        Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<String>>>> empty =
                _ -> CompletableFuture.completedFuture(Map.of());
        assertThatThrownBy(() -> service.defineStream("bad-version", Scope.global(), Resolution.daily(), TypeToken.of(String.class), 0, empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version must be in");
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
    void deleteOlderThan_removesSlotsStrictlyBeforeCutoff_acrossAllScopesAndStreams() {
        var user = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(),
                                        Map.of(SLOT_APR_1, new TestRow("d", 1, "u"),
                                               SLOT_APR_2, new TestRow("d", 2, "u"),
                                               SLOT_APR_3, new TestRow("d", 3, "u")));
        var global = defineStreamWithSeed(STREAM_B, Scope.global(), Resolution.daily(), Map.of(SLOT_APR_1, new TestRow("d", 1, "g")));
        compose(user, SLOT_APR_1, SLOT_APR_3);
        compose(global, SLOT_APR_1, SLOT_APR_1);

        int deletedBeforeApr3 = service.deleteOlderThan(SLOT_APR_3).orTimeout(5, SECONDS).join();
        // Strictly before APR_3, across both scopes: user APR_1 + APR_2 and global APR_1. The user's APR_3 row is on the cutoff, so it survives (exclusive).
        assertThat(deletedBeforeApr3).isEqualTo(3);

        int deletedBeforeApr5 = service.deleteOlderThan(SLOT_APR_5).orTimeout(5, SECONDS).join();
        // Only the surviving user APR_3 row is below APR_5 — proof the first (cutoff-exclusive) purge retained the on-cutoff slot.
        assertThat(deletedBeforeApr5).isEqualTo(1);
    }

    @Test
    void deleteOlderThan_doesNotEvictStreamHandles() {
        var stream = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of(SLOT_APR_1, new TestRow("d", 1, "u")));
        compose(stream, SLOT_APR_1, SLOT_APR_1);

        service.deleteOlderThan(SLOT_APR_5).orTimeout(5, SECONDS).join();

        // Unlike deleteAllForScope/deleteAllForStream, the time-based purge leaves the handle live: re-defining the same (streamId, scope) returns the same
        // instance, not a fresh one.
        var same = defineStreamWithSeed(STREAM_A, Scope.user("user-1"), Resolution.daily(), Map.of(SLOT_APR_1, new TestRow("d", 9, "u")));
        assertThat(same).isSameAs(stream);
    }

    @Test
    void deleteOlderThan_null_throws() {
        assertThatThrownBy(() -> service.deleteOlderThan(null)).isInstanceOf(NullPointerException.class);
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
        // shared read registry. Demonstrates that wire-format migration is forwards-and-backwards compatible per the codec abstraction.
        var jsonWriter = newCache(new CodecRegistry(jsonCodec, ImmutableList.of(jsonCodec, smileCodec)));
        jsonWriter.start();
        try {
            var stream = jsonWriter.defineStream(STREAM_A, Scope.user("user-1"), Resolution.daily(), ROW_TYPE,
                                                 missingSlots -> {
                                                     var out = new HashMap<Instant, Optional<TestRow>>();
                                                     for (Instant slot : missingSlots) {
                                                         out.put(slot, Optional.of(new TestRow("2026-04-02", 9, "json")));
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

    @CacheSchemaVersion(1)
    public record TestRow(String date, int n, String label) {}
}
