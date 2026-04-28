package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.misc.UniqueId;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.Dependency;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.DomainName;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.Executor;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.Migrator;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.SchemaVersion;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

@SuppressWarnings("JDBCPrepareStatementWithNonConstantString")
public final class AdminAlertServiceImpl extends BaseLifecycleComponent implements AdminAlertService {
    private static final Logger logger = LogManager.getLogger(AdminAlertServiceImpl.class);

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final TypeToken<Map<String, String>> LABELS_TYPE = new TypeToken<>() {};

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final PersistenceDomainService persistenceDomainService;
    private final CurrentDateTimeProvider timeProvider;
    private final PersistenceDomainConfig domainConfig;
    private final String insertAlertSql;
    private final String bumpActiveAlertSql;
    private final String updateActiveAlertSql;
    private final String resolveByDedupKeySql;
    private final String resolveByIdSql;
    private final String selectAlertByIdSql;
    private final String deleteResolvedOlderThanSql;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public AdminAlertServiceImpl(@Dependency DataSourceFactory dataSourceFactory,
                                 @Executor Provider<SchedulingExecutor> executorProvider,
                                 PersistenceDomainService persistenceDomainService,
                                 CurrentDateTimeProvider timeProvider,
                                 @SchemaVersion int schemaVersion,
                                 @DomainName String domainName,
                                 @Migrator PersistenceDomainMigrator migrator) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        var domain = new PersistenceDomain(checkNotNull(domainName, "domainName"));
        domainConfig = new PersistenceDomainConfig(domain, schemaVersion, AdminAlertSchema.INIT_STATEMENTS, checkNotNull(migrator, "migrator"));
        String alertTable = domain.prefix() + "alert";
        String severityType = domain.prefix() + "severity";
        insertAlertSql = "INSERT INTO " + alertTable +
                         " (id, dedup_key, title, description, severity, labels, first_seen_at, last_seen_at, update_count) " +
                         "VALUES (?,?,?,?,?::" + severityType + ",?::jsonb,?,?,1)";
        bumpActiveAlertSql = "UPDATE " + alertTable +
                             " SET last_seen_at=?, update_count=update_count+1 " +
                             "WHERE dedup_key=? AND resolved_at IS NULL " +
                             "RETURNING id";
        updateActiveAlertSql = "UPDATE " + alertTable +
                               " SET description=COALESCE(?, description), labels=COALESCE(?::jsonb, labels), " +
                               "last_seen_at=?, update_count=update_count+1 " +
                               "WHERE dedup_key=? AND resolved_at IS NULL " +
                               "RETURNING id";
        resolveByDedupKeySql = "UPDATE " + alertTable +
                               " SET resolved_at=?, resolved_by=?, resolution_note=? " +
                               "WHERE dedup_key=? AND resolved_at IS NULL " +
                               "RETURNING id";
        // Single-statement CTE: branch on existence-and-state in one round-trip with a snapshot-consistent view (Postgres evaluates all CTEs against the
        //  same snapshot), so there is no race between the existence check and the resolve. Returns 'RESOLVED' / 'ALREADY_RESOLVED' / 'UNKNOWN'.
        resolveByIdSql = "WITH target AS (SELECT id FROM " + alertTable + " WHERE id=?), " +
                         "updated AS (UPDATE " + alertTable +
                         " SET resolved_at=?, resolved_by=?, resolution_note=? WHERE id=? AND resolved_at IS NULL RETURNING 1) " +
                         "SELECT CASE " +
                         "WHEN NOT EXISTS (SELECT 1 FROM target) THEN 'UNKNOWN' " +
                         "WHEN EXISTS (SELECT 1 FROM updated) THEN 'RESOLVED' " +
                         "ELSE 'ALREADY_RESOLVED' END";
        selectAlertByIdSql = "SELECT id, dedup_key, title, description, severity, labels, first_seen_at, last_seen_at, update_count, " +
                             "resolved_at, resolved_by, resolution_note " +
                             "FROM " + alertTable + " WHERE id=?";
        deleteResolvedOlderThanSql = "DELETE FROM " + alertTable + " WHERE resolved_at IS NOT NULL AND resolved_at < ?";
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

    @Override
    public CompletableFuture<String> raise(AdminAlertData data) {
        checkNotNull(data, "data");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doRaise(data)));
    }

    @Override
    public CompletableFuture<Optional<String>> update(String dedupKey, AdminAlertUpdate update) {
        validateDedupKey(dedupKey);
        checkNotNull(update, "update");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doUpdate(dedupKey, update)));
    }

    @Override
    public CompletableFuture<Optional<String>> resolve(String dedupKey, Optional<String> note) {
        validateDedupKey(dedupKey);
        checkNotNull(note, "note");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doResolve(dedupKey, note)));
    }

    @Override
    public CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note) {
        validateAlertId(alertId);
        validateResolvedBy(resolvedBy);
        checkNotNull(note, "note");
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doResolveById(alertId, resolvedBy, note)));
    }

    @Override
    public CompletableFuture<Optional<AdminAlert>> getById(String alertId) {
        validateAlertId(alertId);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doGetById(alertId)));
    }

    @Override
    public CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention) {
        checkNotNull(retention, "retention");
        checkArgument(retention.isPositive(), "retention must be positive, was %s", retention);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doDeleteResolvedOlderThan(retention)));
    }

    private String doRaise(AdminAlertData data) {
        Instant now = timeProvider.currentInstant();
        String newId = UniqueId.generate('a');
        String labelsJson = Json.stringify(data.labels());
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = connection.prepareStatement(insertAlertSql)) {
                    stmt.setString(1, newId);
                    stmt.setString(2, data.dedupKey());
                    stmt.setString(3, data.title());
                    stmt.setString(4, data.description());
                    stmt.setString(5, data.severity().name());
                    stmt.setString(6, labelsJson);
                    stmt.setTimestamp(7, Timestamp.from(now));
                    stmt.setTimestamp(8, Timestamp.from(now));
                    stmt.executeUpdate();
                    connection.commit();
                    return newId;
                } catch (SQLException e) {
                    rollbackQuietly(connection);
                    if (!isUniqueViolation(e)) {
                        throw new RuntimeException("Failed to insert alert with dedupKey " + data.dedupKey(), e);
                    }
                }
            } finally {
                resetAutoCommit(connection);
            }
            String existingId = bumpActiveAlert(connection, data.dedupKey(), now);
            checkState(existingId != null, "raise: dedupKey %s collided then disappeared", data.dedupKey());
            return existingId;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to raise alert with dedupKey " + data.dedupKey(), e);
        }
    }

    private @Nullable String bumpActiveAlert(Connection connection, String dedupKey, Instant now) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(bumpActiveAlertSql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            stmt.setString(2, dedupKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private Optional<String> doUpdate(String dedupKey, AdminAlertUpdate update) {
        Instant now = timeProvider.currentInstant();
        String labelsJson = update.labels().map(Json::stringify).orElse(null);
        String description = update.description().orElse(null);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(updateActiveAlertSql)) {
            stmt.setString(1, description);
            stmt.setString(2, labelsJson);
            stmt.setTimestamp(3, Timestamp.from(now));
            stmt.setString(4, dedupKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update alert with dedupKey " + dedupKey, e);
        }
    }

    private Optional<String> doResolve(String dedupKey, Optional<String> note) {
        Instant now = timeProvider.currentInstant();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(resolveByDedupKeySql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            stmt.setString(2, "system");
            stmt.setString(3, note.orElse(null));
            stmt.setString(4, dedupKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve alert with dedupKey " + dedupKey, e);
        }
    }

    private ResolveByIdOutcome doResolveById(String alertId, String resolvedBy, Optional<String> note) {
        Instant now = timeProvider.currentInstant();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(resolveByIdSql)) {
            stmt.setString(1, alertId);
            stmt.setTimestamp(2, Timestamp.from(now));
            stmt.setString(3, resolvedBy);
            stmt.setString(4, note.orElse(null));
            stmt.setString(5, alertId);
            try (ResultSet rs = stmt.executeQuery()) {
                checkState(rs.next(), "resolveById CTE returned no row for alertId %s", alertId);
                return ResolveByIdOutcome.valueOf(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve alert with id " + alertId, e);
        }
    }

    private Optional<AdminAlert> doGetById(String alertId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(selectAlertByIdSql)) {
            stmt.setString(1, alertId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(mapAlert(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alert with id " + alertId, e);
        }
    }

    private int doDeleteResolvedOlderThan(Duration retention) {
        Instant cutoff = timeProvider.currentInstant().minus(retention);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(deleteResolvedOlderThanSql)) {
            stmt.setTimestamp(1, Timestamp.from(cutoff));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete resolved alerts older than " + cutoff, e);
        }
    }

    private static AdminAlert mapAlert(ResultSet rs) throws SQLException {
        AdminAlert.Builder builder = AdminAlert.builder()
                                               .setId(rs.getString("id"))
                                               .setDedupKey(rs.getString("dedup_key"))
                                               .setTitle(rs.getString("title"))
                                               .setDescription(rs.getString("description"))
                                               .setSeverity(AdminAlertSeverity.valueOf(rs.getString("severity")))
                                               .setLabels(deserialiseLabels(rs.getString("labels")))
                                               .setFirstSeenAt(rs.getTimestamp("first_seen_at").toInstant())
                                               .setLastSeenAt(rs.getTimestamp("last_seen_at").toInstant())
                                               .setUpdateCount(rs.getInt("update_count"));
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        if (resolvedAt != null) {
            builder.setResolvedAt(resolvedAt.toInstant());
        }
        String resolvedBy = rs.getString("resolved_by");
        if (resolvedBy != null) {
            builder.setResolvedBy(resolvedBy);
        }
        String resolutionNote = rs.getString("resolution_note");
        if (resolutionNote != null) {
            builder.setResolutionNote(resolutionNote);
        }
        return builder.build();
    }

    private static Map<String, String> deserialiseLabels(@Nullable String labelsJson) {
        if (labelsJson == null || labelsJson.isBlank()) {
            return ImmutableMap.of();
        }
        return ImmutableMap.copyOf(Json.parse(labelsJson, LABELS_TYPE));
    }

    private static boolean isUniqueViolation(SQLException exception) {
        return UNIQUE_VIOLATION_SQL_STATE.equals(exception.getSQLState());
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            logger.info("Rollback failed", e);
        }
    }

    private static void resetAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    private static void validateDedupKey(String dedupKey) {
        checkNotNull(dedupKey, "dedupKey");
        checkArgument(!dedupKey.isBlank(), "dedupKey must not be blank");
    }

    private static void validateAlertId(String alertId) {
        checkNotNull(alertId, "alertId");
        checkArgument(!alertId.isBlank(), "alertId must not be blank");
    }

    private static void validateResolvedBy(String resolvedBy) {
        checkNotNull(resolvedBy, "resolvedBy");
        checkArgument(!resolvedBy.isBlank(), "resolvedBy must not be blank");
    }
}
