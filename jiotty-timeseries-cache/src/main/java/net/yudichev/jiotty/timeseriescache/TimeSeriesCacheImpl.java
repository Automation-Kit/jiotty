package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
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
    private final PersistenceDomainConfig domainConfig;
    private final String upsertSql;
    private final String selectRangeSql;
    private final String deleteAllForScopeSql;
    private final String deleteAllForStreamSql;
    private final String deleteOlderThanSql;
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
                               @SchemaVersion int schemaVersion,
                               @DomainName String domainName,
                               @Migrator PersistenceDomainMigrator migrator) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        this.codecRegistry = checkNotNull(codecRegistry, "codecRegistry");
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
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        asUnchecked(() -> persistenceDomainService.ensureDomainReady(domainConfig).get(30, SECONDS));
        dataSource = dataSourceFactory.create();
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
                                                Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        checkArgument(streamId != null && !streamId.isBlank(), "streamId must be non-blank");
        checkNotNull(scope, "scope");
        checkNotNull(resolution, "resolution");
        checkNotNull(type, "type");
        checkNotNull(slotsComputation, "slotsComputation");
        TimeSeriesStreamImpl<?> existingStream = streamsByKey.compute(new StreamKey(streamId, scope), (key, currentStream) -> {
            if (currentStream == null) {
                return new TimeSeriesStreamImpl<>(this, key.streamId(), key.scope(), resolution, type, slotsComputation);
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
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(selectRangeSql)) {
            bindScopeColumns(stmt, stream.scope());
            stmt.setString(3, stream.streamId());
            stmt.setObject(4, fromInclusive.atOffset(ZoneOffset.UTC));
            stmt.setObject(5, toInclusive.atOffset(ZoneOffset.UTC));
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Instant slotStart = rs.getObject(1, OffsetDateTime.class).toInstant();
                    // decode yields Optional.empty() for a tombstone row (a known-empty hit that must not be recomputed) and Optional.of(value) otherwise.
                    out.put(slotStart, codecRegistry.decode(rs.getBytes(2), stream.type()));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read time-series cache range", e);
        }
        logger.debug("[{}][{}] readRange {}..{} hits={}", stream.scope(), stream.streamId(), fromInclusive, toInclusive, out.size());
        return out;
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

                // encode frames the value, or a tombstone for an empty Optional. setBinaryStream over a ByteArrayInputStream avoids pgjdbc's defensive
                //  byte[] copy that setBytes(byte[]) performs, so batch memory stays at one byte[] per row; the frame is only read here, never mutated.
                byte[] payload = codecRegistry.encode(entry.getValue());
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
