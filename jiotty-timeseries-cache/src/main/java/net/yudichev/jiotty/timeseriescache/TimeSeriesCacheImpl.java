package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.adminalerts.AdminAlertData;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheModule.Dependency;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheModule.DomainName;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheModule.Executor;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheModule.Migrator;
import static net.yudichev.jiotty.timeseriescache.TimeSeriesCacheModule.SchemaVersion;

@SuppressWarnings({"JDBCPrepareStatementWithNonConstantString",
        "WeakerAccess"}) // public outer surface on a package-private class — see java-style "internal APIs on non-public types" rule
final class TimeSeriesCacheImpl extends BaseLifecycleComponent implements TimeSeriesCache {
    private static final Logger logger = LogManager.getLogger(TimeSeriesCacheImpl.class);

    private static final short SCOPE_KIND_GLOBAL = 0;
    private static final short SCOPE_KIND_USER = 1;
    private static final short SCOPE_KIND_REGION = 2;

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final PersistenceDomainService persistenceDomainService;
    private final CurrentDateTimeProvider timeProvider;
    private final CodecRegistry codecRegistry;
    private final AdminAlertService adminAlertService;
    private final PersistenceDomainConfig domainConfig;
    private final String upsertSql;
    private final String selectRangeSql;
    private final String deleteAllForScopeSql;
    private final String deleteAllForStreamSql;
    private final String deleteOlderThanSql;
    private final String deleteSlotsSql;
    /// Registry of live stream handles. Keyed by `(streamId, scope)` because the same logical streamId can have N concurrent registrations across different
    /// users / regions / global. [#deleteAllForScope] and [#deleteAllForStream] sweep matching entries so handles bound to a deleted scope don't linger
    /// pointing at empty rows.
    private final Map<StreamKey, TimeSeriesStreamImpl<?>> streamsByKey = new ConcurrentHashMap<>();

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public TimeSeriesCacheImpl(@Dependency DataSourceFactory dataSourceFactory,
                               @Executor Provider<SchedulingExecutor> executorProvider,
                               PersistenceDomainService persistenceDomainService,
                               CurrentDateTimeProvider timeProvider,
                               CodecRegistry codecRegistry,
                               @Dependency AdminAlertService adminAlertService,
                               @SchemaVersion int schemaVersion,
                               @DomainName String domainName,
                               @Migrator PersistenceDomainMigrator migrator) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        this.codecRegistry = checkNotNull(codecRegistry, "codecRegistry");
        this.adminAlertService = checkNotNull(adminAlertService, "adminAlertService");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        var domain = new PersistenceDomain(checkNotNull(domainName, "domainName"));
        domainConfig = new PersistenceDomainConfig(domain, schemaVersion, TimeSeriesCacheSchema.INIT_STATEMENTS, checkNotNull(migrator, "migrator"));
        // SQL identifier — safe to concatenate: `domain.prefix()` is `<domain.name>_` where `domain.name` is constrained by [PersistenceDomain] to
        //  `[A-Za-z0-9_]+`, so `entryTable` matches the same shape. Every per-call data value below goes through `PreparedStatement.setX(...)`.
        String entryTable = domain.prefix() + "entry";
        // `scope_value IS NOT DISTINCT FROM ?` matches across NULL/non-NULL — required because the column is nullable for [Scope.Global] rows. The UK
        // covering the same column list with NULLS NOT DISTINCT (see [TimeSeriesCacheSchema]) keeps ON CONFLICT inference unambiguous.
        upsertSql = "INSERT INTO " + entryTable +
                    " (scope_kind, scope_value, stream_id, slot_start, value, created_at, updated_at) " +
                    "VALUES (?,?,?,?,?,?,?) " +
                    "ON CONFLICT (scope_kind, scope_value, stream_id, slot_start) " +
                    "DO UPDATE SET value=EXCLUDED.value, updated_at=EXCLUDED.updated_at";
        selectRangeSql = "SELECT slot_start, value FROM " + entryTable +
                         " WHERE scope_kind=? AND scope_value IS NOT DISTINCT FROM ? AND stream_id=? AND slot_start BETWEEN ? AND ?";
        deleteAllForScopeSql = "DELETE FROM " + entryTable + " WHERE scope_kind=? AND scope_value IS NOT DISTINCT FROM ?";
        deleteAllForStreamSql = "DELETE FROM " + entryTable + " WHERE stream_id=?";
        deleteOlderThanSql = "DELETE FROM " + entryTable + " WHERE slot_start < ?";
        deleteSlotsSql = "DELETE FROM " + entryTable +
                         " WHERE scope_kind=? AND scope_value IS NOT DISTINCT FROM ? AND stream_id=? AND slot_start = ANY(?)";
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        asUnchecked(() -> persistenceDomainService.ensureDomainReady(domainConfig).get(30, SECONDS));
        dataSource = dataSourceFactory.create("time-series");
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> TimeSeriesStream<T> defineStream(String streamId,
                                                Scope scope,
                                                Resolution resolution,
                                                TypeToken<T> type,
                                                int schemaVersion,
                                                Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        checkArgument(streamId != null && !streamId.isBlank(), "streamId must be non-blank");
        checkNotNull(scope, "scope");
        checkNotNull(resolution, "resolution");
        checkNotNull(type, "type");
        CacheSchemaVersions.checkVersion(schemaVersion);
        checkNotNull(slotsComputation, "slotsComputation");
        TimeSeriesStreamImpl<?> existingStream = streamsByKey.compute(new StreamKey(streamId, scope), (key, currentStream) -> {
            if (currentStream == null) {
                return new TimeSeriesStreamImpl<>(this, key.streamId(), key.scope(), resolution, type, schemaVersion, slotsComputation);
            }
            checkArgument(currentStream.resolution().equals(resolution),
                          "stream '%s' for scope %s already defined with resolution %s; conflicting redefinition with %s",
                          key.streamId(), key.scope(), currentStream.resolution(), resolution);
            checkArgument(currentStream.type().getType().equals(type.getType()),
                          "stream '%s' for scope %s already defined with type %s; conflicting redefinition with %s",
                          key.streamId(), key.scope(), currentStream.type(), type);
            return currentStream;
        });
        return (TimeSeriesStream<T>) existingStream;
    }

    @Override
    public CompletableFuture<Integer> deleteAllForScope(Scope scope) {
        checkNotNull(scope, "scope");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doDeleteAllForScope(scope)));
    }

    @Override
    public CompletableFuture<Integer> deleteAllForStream(String streamId) {
        checkArgument(streamId != null && !streamId.isBlank(), "streamId must be non-blank");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doDeleteAllForStream(streamId)));
    }

    @Override
    public CompletableFuture<Integer> deleteOlderThan(Instant cutoffExclusive) {
        checkNotNull(cutoffExclusive, "cutoffExclusive");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doDeleteOlderThan(cutoffExclusive)));
    }

    <T> CompletableFuture<Map<Instant, Optional<T>>> readRange(TimeSeriesStreamImpl<T> stream, Instant fromInclusive, Instant toInclusive) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doReadRange(stream, fromInclusive, toInclusive)));
    }

    <T> CompletableFuture<Void> writeBatch(TimeSeriesStreamImpl<T> stream, Map<Instant, Optional<T>> values) {
        checkNotNull(values, "values");
        if (values.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            doWriteBatch(stream, values);
            return null;
        }));
    }

    private <T> Map<Instant, Optional<T>> doReadRange(TimeSeriesStreamImpl<T> stream, Instant fromInclusive, Instant toInclusive) {
        var out = new HashMap<Instant, Optional<T>>();
        var slotsToWipe = new ArrayList<Instant>();
        boolean alarming = false;
        String firstDiscardReason = null;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement stmt = connection.prepareStatement(selectRangeSql)) {
                bindScopeColumns(stmt, stream.scope());
                stmt.setString(3, stream.streamId());
                stmt.setObject(4, fromInclusive.atOffset(ZoneOffset.UTC));
                stmt.setObject(5, toInclusive.atOffset(ZoneOffset.UTC));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Instant slotStart = rs.getObject(1, OffsetDateTime.class).toInstant();
                        // Present → cached value; Empty → tombstone (a known-empty hit, not recomputed); Discard → an undecodable row (stale schema version or
                        //  corruption): omit it so the slot recomputes, and collect it for a batch wipe so the bad bytes don't linger and re-trip every read.
                        switch (codecRegistry.decode(rs.getBytes(2), stream.type(), stream.schemaVersion())) {
                            case DecodeOutcome.Present<T>(T value) -> out.put(slotStart, Optional.of(value));
                            case DecodeOutcome.Empty<T> _ -> out.put(slotStart, Optional.empty());
                            case DecodeOutcome.Discard<T>(boolean rowAlarming, String reason) -> {
                                slotsToWipe.add(slotStart);
                                alarming = alarming || rowAlarming;
                                if (firstDiscardReason == null) {
                                    firstDiscardReason = reason;
                                }
                            }
                        }
                    }
                }
            }
            if (!slotsToWipe.isEmpty()) {
                wipeSlots(connection, stream, slotsToWipe);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read time-series cache range", e);
        }
        if (!slotsToWipe.isEmpty()) {
            logger.info("[{}][{}] discarded and wiped {} undecodable row(s) (alarming={}); first reason: {}",
                        stream.scope(), stream.streamId(), slotsToWipe.size(), alarming, firstDiscardReason);
            if (alarming) {
                raiseUndecodableRowsAlert(stream, slotsToWipe.size(), firstDiscardReason);
            }
        }
        logger.debug("[{}][{}] readRange {}..{} hits={}", stream.scope(), stream.streamId(), fromInclusive, toInclusive, out.size());
        return out;
    }

    /// Deletes the given slots (undecodable rows) in one round-trip on the read's own connection, so the wipe shares the read's transaction and the bad bytes
    /// are gone before the stream recomputes them.
    private void wipeSlots(Connection connection, TimeSeriesStreamImpl<?> stream, List<Instant> slots) throws SQLException {
        var slotArray = new OffsetDateTime[slots.size()];
        for (int i = 0; i < slots.size(); i++) {
            slotArray[i] = slots.get(i).atOffset(ZoneOffset.UTC);
        }
        try (PreparedStatement stmt = connection.prepareStatement(deleteSlotsSql)) {
            bindScopeColumns(stmt, stream.scope());
            stmt.setString(3, stream.streamId());
            Array array = connection.createArrayOf("timestamptz", slotArray);
            stmt.setArray(4, array);
            stmt.executeUpdate();
        }
    }

    /// Raises a single PII-free admin alert for a batch of genuinely-corrupt rows (a stale schema version is *not* alarming and never reaches here). The alert
    /// carries only the stream *family* (the prefix before the first `:`, which is PII-free even when the full streamId embeds an MPAN / meter serial), the
    /// scope kind (never the userId / region value), the discarded-row count, and a frame-metadata-only reason — never row contents.
    private void raiseUndecodableRowsAlert(TimeSeriesStreamImpl<?> stream, int discardedCount, String firstReason) {
        String family = streamFamily(stream.streamId());
        String scopeKind = scopeKind(stream.scope());
        adminAlertService.raise(AdminAlertData.builder()
                                              .setSeverity(AdminAlertSeverity.ERROR)
                                              .setTitle("Time-series cache: undecodable rows discarded")
                                              .setDescription("Discarded " + discardedCount + " undecodable row(s) from stream family '" + family +
                                                              "' (scope " + scopeKind + "); first failure: " + firstReason +
                                                              ". Rows were wiped and will be recomputed.")
                                              .setLabels(ImmutableMap.of("streamFamily", family, "scopeKind", scopeKind))
                                              .build());
    }

    private static String scopeKind(Scope scope) {
        return switch (scope) {
            case Scope.Global _ -> "global";
            case Scope.User _ -> "user";
            case Scope.Region _ -> "region";
        };
    }

    /// The PII-free prefix of a streamId — the part before the first `:`, which by convention is the stream family (e.g. `octopus.consumption`,
    /// `octopus.rates`, `savings`) and never the variant suffix that may embed an MPAN / meter serial / region code.
    private static String streamFamily(String streamId) {
        int colon = streamId.indexOf(':');
        return colon < 0 ? streamId : streamId.substring(0, colon);
    }

    private <T> void doWriteBatch(TimeSeriesStreamImpl<T> stream, Map<Instant, Optional<T>> values) {
        Instant now = timeProvider.currentInstant();
        Timestamp nowTs = Timestamp.from(now);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(upsertSql)) {
            for (Map.Entry<Instant, Optional<T>> entry : values.entrySet()) {
                bindScopeColumns(stmt, stream.scope());
                stmt.setString(3, stream.streamId());
                stmt.setObject(4, entry.getKey().atOffset(ZoneOffset.UTC));

                // encode frames the value at the stream's current schema version, or a tombstone for an empty Optional. setBinaryStream over a
                //  ByteArrayInputStream avoids pgjdbc's defensive byte[] copy that setBytes(byte[]) performs, so batch memory stays at one byte[] per row; the
                //  frame is only read here, never mutated.
                byte[] payload = codecRegistry.encode(entry.getValue(), stream.schemaVersion());
                stmt.setBinaryStream(5, new ByteArrayInputStream(payload), payload.length);

                stmt.setTimestamp(6, nowTs);
                stmt.setTimestamp(7, nowTs);
                stmt.addBatch();
            }
            int[] results = stmt.executeBatch();
            logger.debug("[{}][{}] writeBatch rows={}", stream.scope(), stream.streamId(), results.length);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to write time-series cache batch", e);
        }
    }

    /// Binds the two scope-discriminator columns (`scope_kind`, `scope_value`) at parameter positions 1, 2 of `stmt`. Every per-row SQL string in this class
    /// places these columns first, so callers bind from index 3 onward. [Scope.Global] writes NULL for `scope_value`; [Scope.User] and [Scope.Region] write the
    /// variant's payload (userId / region code).
    private static void bindScopeColumns(PreparedStatement stmt, Scope scope) throws SQLException {
        switch (scope) {
            case Scope.Global _ -> {
                stmt.setShort(1, SCOPE_KIND_GLOBAL);
                stmt.setString(2, null);
            }
            case Scope.User user -> {
                stmt.setShort(1, SCOPE_KIND_USER);
                stmt.setString(2, user.userId());
            }
            case Scope.Region region -> {
                stmt.setShort(1, SCOPE_KIND_REGION);
                stmt.setString(2, region.code());
            }
        }
    }

    private int doDeleteAllForScope(Scope scope) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(deleteAllForScopeSql)) {
            bindScopeColumns(stmt, scope);
            int deleted = stmt.executeUpdate();
            streamsByKey.keySet().removeIf(key -> key.scope().equals(scope));
            logger.info("Time-series cache: deleted {} row(s) for scope {}", deleted, scope);
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete time-series cache entries for scope " + scope, e);
        }
    }

    private int doDeleteAllForStream(String streamId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(deleteAllForStreamSql)) {
            stmt.setString(1, streamId);
            int deleted = stmt.executeUpdate();
            streamsByKey.keySet().removeIf(key -> key.streamId().equals(streamId));
            logger.info("Time-series cache: deleted {} row(s) for stream {}", deleted, streamId);
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete time-series cache entries for stream " + streamId, e);
        }
    }

    private int doDeleteOlderThan(Instant cutoffExclusive) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(deleteOlderThanSql)) {
            stmt.setObject(1, cutoffExclusive.atOffset(ZoneOffset.UTC));
            int deleted = stmt.executeUpdate();
            // No streamsByKey eviction: this purge spans live streams, so the handles stay valid — a re-read of a purged past slot recomputes via the
            //  stream's slotsComputation.
            logger.info("Time-series cache: deleted {} row(s) older than {}", deleted, cutoffExclusive);
            return deleted;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete time-series cache entries older than " + cutoffExclusive, e);
        }
    }

    private record StreamKey(String streamId, Scope scope) {}
}
