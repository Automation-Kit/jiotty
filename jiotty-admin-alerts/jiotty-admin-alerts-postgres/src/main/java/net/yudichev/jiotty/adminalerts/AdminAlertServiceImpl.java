package net.yudichev.jiotty.adminalerts;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
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
import org.jspecify.annotations.Nullable;

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
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.MaxBundles;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.MaxEventsPerBundle;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.Migrator;
import static net.yudichev.jiotty.adminalerts.AdminAlertServiceModule.SchemaVersion;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

@SuppressWarnings("JDBCPrepareStatementWithNonConstantString")
public final class AdminAlertServiceImpl extends BaseLifecycleComponent implements AdminAlertService {
    private static final Logger logger = LogManager.getLogger(AdminAlertServiceImpl.class);

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final TypeToken<Map<String, String>> LABELS_TYPE = new TypeToken<>() {};
    private static final long PID = ProcessHandle.current().pid();
    private static final String PID_LABEL = "pid";

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final PersistenceDomainService persistenceDomainService;
    private final CurrentDateTimeProvider timeProvider;
    private final int maxBundles;
    private final int maxEventsPerBundle;
    private final PersistenceDomainConfig domainConfig;
    private final String insertBundleSql;
    private final String insertEventSql;
    private final String bumpActiveBundleSql;
    private final String resolveByDedupKeySql;
    private final String resolveByIdSql;
    private final String selectAlertByKeySql;
    private final String selectActiveBundleIdByKeySql;
    private final String deleteResolvedOlderThanSql;
    private final String countBundlesSql;
    private final String countEventsForBundleSql;
    private final String deleteOldestBundlesSql;
    private final String deleteOldestEventsForBundleSql;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public AdminAlertServiceImpl(@Dependency DataSourceFactory dataSourceFactory,
                                 @Executor Provider<SchedulingExecutor> executorProvider,
                                 PersistenceDomainService persistenceDomainService,
                                 CurrentDateTimeProvider timeProvider,
                                 @SchemaVersion int schemaVersion,
                                 @DomainName String domainName,
                                 @Migrator PersistenceDomainMigrator migrator,
                                 @MaxBundles int maxBundles,
                                 @MaxEventsPerBundle int maxEventsPerBundle) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory, "dataSourceFactory");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.persistenceDomainService = checkNotNull(persistenceDomainService, "persistenceDomainService");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        checkArgument(schemaVersion > 0, "schemaVersion must be > 0, was %s", schemaVersion);
        checkArgument(maxBundles > 0, "maxBundles must be > 0, was %s", maxBundles);
        checkArgument(maxEventsPerBundle > 0, "maxEventsPerBundle must be > 0, was %s", maxEventsPerBundle);
        this.maxBundles = maxBundles;
        this.maxEventsPerBundle = maxEventsPerBundle;
        var domain = new PersistenceDomain(checkNotNull(domainName, "domainName"));
        domainConfig = new PersistenceDomainConfig(domain, schemaVersion, AdminAlertSchema.INIT_STATEMENTS, checkNotNull(migrator, "migrator"));
        String alertTable = domain.prefix() + "alert";
        String eventTable = domain.prefix() + "alert_event";
        String severityType = domain.prefix() + "severity";
        insertBundleSql = "INSERT INTO " + alertTable +
                          " (id, dedup_key, title, severity, labels, first_seen_at, last_seen_at, event_count) " +
                          "VALUES (?,?,?,?::" + severityType + ",?::jsonb,?,?,1)";
        insertEventSql = "INSERT INTO " + eventTable + " (alert_id, occurred_at, description) VALUES (?,?,?)";
        bumpActiveBundleSql = "UPDATE " + alertTable +
                              " SET last_seen_at=?, event_count=event_count+1 " +
                              "WHERE dedup_key=? AND resolved_at IS NULL " +
                              "RETURNING id";
        selectActiveBundleIdByKeySql = "SELECT id FROM " + alertTable + " WHERE dedup_key=? AND resolved_at IS NULL";
        resolveByDedupKeySql = "UPDATE " + alertTable +
                               " SET resolved_at=?, resolved_by=?, resolution_note=? " +
                               "WHERE dedup_key=? AND resolved_at IS NULL " +
                               "RETURNING id";
        // Single-statement CTE: branch on existence-and-state in one round-trip with a snapshot-consistent view (Postgres evaluates all CTEs against the
        // same snapshot), so there is no race between the existence check and the resolve. Returns 'RESOLVED' / 'ALREADY_RESOLVED' / 'UNKNOWN'.
        resolveByIdSql = "WITH target AS (SELECT id FROM " + alertTable + " WHERE id=?), " +
                         "updated AS (UPDATE " + alertTable +
                         " SET resolved_at=?, resolved_by=?, resolution_note=? WHERE id=? AND resolved_at IS NULL RETURNING 1) " +
                         "SELECT CASE " +
                         "WHEN NOT EXISTS (SELECT 1 FROM target) THEN 'UNKNOWN' " +
                         "WHEN EXISTS (SELECT 1 FROM updated) THEN 'RESOLVED' " +
                         "ELSE 'ALREADY_RESOLVED' END";
        selectAlertByKeySql = "SELECT id, dedup_key, title, severity, labels, first_seen_at, last_seen_at, event_count, " +
                              "resolved_at, resolved_by, resolution_note " +
                              "FROM " + alertTable + " WHERE dedup_key=? ORDER BY first_seen_at DESC LIMIT 1";
        deleteResolvedOlderThanSql = "DELETE FROM " + alertTable + " WHERE resolved_at IS NOT NULL AND resolved_at < ?";
        countBundlesSql = "SELECT count(*) FROM " + alertTable;
        countEventsForBundleSql = "SELECT count(*) FROM " + eventTable + " WHERE alert_id=?";
        deleteOldestBundlesSql = "DELETE FROM " + alertTable +
                                 " WHERE id IN (SELECT id FROM " + alertTable + " ORDER BY first_seen_at ASC LIMIT ?)";
        // `id` is a bigserial, so it breaks a tie between two events sharing an instant by insertion order.
        deleteOldestEventsForBundleSql = "DELETE FROM " + eventTable +
                                         " WHERE id IN (SELECT id FROM " + eventTable +
                                         " WHERE alert_id=? ORDER BY occurred_at ASC, id ASC LIMIT ?)";
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        asUnchecked(() -> persistenceDomainService.ensureDomainReady(domainConfig).get(30, SECONDS));
        dataSource = dataSourceFactory.create("admin-alerts");
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource);
    }

    @Override
    public String raise(AdminAlertData data) {
        checkNotNull(data, "data");
        return whenStartedAndNotLifecycling(() -> {
            AdminAlertData effectiveData = augmentWithFrameworkLabels(data);
            String key = effectiveData.key();
            logger.info("NEW ALERT {}", effectiveData);
            // Stamped on the calling thread: the write runs on the executor, which under a backlog lands later
            // than the event it describes, and both eviction orders sort on these instants.
            Instant raisedAt = timeProvider.currentInstant();
            executor.submit(() -> doRaise(effectiveData, raisedAt))
                    .whenComplete((_, error) -> {
                        if (error != null) {
                            // WARN, not INFO, despite the jiotty library log rule: a failed alert raise is an
                            // operationally significant failure (the alert is lost and the admin action will
                            // never happen). Do not demote to INFO.
                            logger.warn("Failed to raise alert with key {}", key, error);
                        }
                    });
            return key;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> resolve(String key, String note) {
        validateKey(key);
        checkNotNull(note, "note");
        return whenStartedAndNotLifecycling(() -> {
            logger.info("SYSTEM RESOLVE ALERT {}: {}", key, note);
            return executor.submit(() -> doResolve(key, note));
        });
    }

    @Override
    public CompletableFuture<ResolveByIdOutcome> resolveById(String alertId, String resolvedBy, Optional<String> note) {
        validateAlertId(alertId);
        validateResolvedBy(resolvedBy);
        checkNotNull(note, "note");
        return whenStartedAndNotLifecycling(() -> {
            logger.info("ADMIN RESOLVE ALERT {} by {}{}", alertId, resolvedBy, note.map(n -> ": " + n).orElse(""));
            return executor.submit(() -> doResolveById(alertId, resolvedBy, note));
        });
    }

    @Override
    public CompletableFuture<Integer> deleteResolvedOlderThan(Duration retention) {
        checkNotNull(retention, "retention");
        checkArgument(retention.isPositive(), "retention must be positive, was %s", retention);
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> doDeleteResolvedOlderThan(retention)));
    }

    @VisibleForTesting
    Optional<AdminAlert> findByKey(String key) {
        validateKey(key);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(selectAlertByKeySql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(mapAlert(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alert with key " + key, e);
        }
    }

    /// `data` here is already augmented with framework labels (see `raise(...)`); we use it as-is.
    private String doRaise(AdminAlertData data, Instant raisedAt) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String existingId = findActiveBundleId(connection, data.key());
                String bundleId;
                if (existingId == null) {
                    bundleId = insertNewBundle(connection, data, raisedAt);
                } else {
                    bumpExistingBundle(connection, data.key(), raisedAt);
                    bundleId = existingId;
                }
                appendEvent(connection, bundleId, raisedAt, data.description());
                connection.commit();
                return bundleId;
            } catch (SQLException e) {
                rollbackQuietly(connection);
                throw new RuntimeException("Failed to raise alert with key " + data.key(), e);
            } finally {
                resetAutoCommit(connection);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to raise alert with key " + data.key(), e);
        }
    }

    private @Nullable String findActiveBundleId(Connection connection, String key) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(selectActiveBundleIdByKeySql)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private String insertNewBundle(Connection connection, AdminAlertData data, Instant raisedAt) throws SQLException {
        enforceBundleCap(connection);
        String newId = UniqueId.generate('a');
        String labelsJson = Json.stringify(data.labels());
        try (PreparedStatement stmt = connection.prepareStatement(insertBundleSql)) {
            stmt.setString(1, newId);
            stmt.setString(2, data.key());
            stmt.setString(3, data.title());
            stmt.setString(4, data.severity().name());
            stmt.setString(5, labelsJson);
            var raisedAtTimestamp = Timestamp.from(raisedAt);
            stmt.setTimestamp(6, raisedAtTimestamp);
            stmt.setTimestamp(7, raisedAtTimestamp);
            try {
                stmt.executeUpdate();
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    // Lost the race against a concurrent raise; fall back to bump path.
                    String existingId = findActiveBundleId(connection, data.key());
                    checkState(existingId != null, "raise: key %s collided then disappeared", data.key());
                    bumpExistingBundle(connection, data.key(), raisedAt);
                    return existingId;
                }
                throw e;
            }
        }
        return newId;
    }

    private void bumpExistingBundle(Connection connection, String key, Instant raisedAt) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(bumpActiveBundleSql)) {
            stmt.setTimestamp(1, Timestamp.from(raisedAt));
            stmt.setString(2, key);
            try (ResultSet rs = stmt.executeQuery()) {
                checkState(rs.next(), "bump: active bundle for key %s disappeared", key);
            }
        }
    }

    private void appendEvent(Connection connection, String bundleId, Instant occurredAt, String description) throws SQLException {
        enforceEventCap(connection, bundleId);
        try (PreparedStatement stmt = connection.prepareStatement(insertEventSql)) {
            stmt.setString(1, bundleId);
            stmt.setTimestamp(2, Timestamp.from(occurredAt));
            stmt.setString(3, description);
            stmt.executeUpdate();
        }
    }

    private void enforceBundleCap(Connection connection) throws SQLException {
        int currentCount = countOne(connection, countBundlesSql);
        int toDelete = currentCount - (maxBundles - 1);
        if (toDelete <= 0) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(deleteOldestBundlesSql)) {
            stmt.setInt(1, toDelete);
            int deleted = stmt.executeUpdate();
            logger.info("Bundle cap enforced: deleted {} oldest bundles to fit cap {}", deleted, maxBundles);
        }
    }

    private void enforceEventCap(Connection connection, String bundleId) throws SQLException {
        int currentCount;
        try (PreparedStatement stmt = connection.prepareStatement(countEventsForBundleSql)) {
            stmt.setString(1, bundleId);
            try (ResultSet rs = stmt.executeQuery()) {
                checkState(rs.next(), "count(*) returned no row");
                currentCount = rs.getInt(1);
            }
        }
        int toDelete = currentCount - (maxEventsPerBundle - 1);
        if (toDelete <= 0) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(deleteOldestEventsForBundleSql)) {
            stmt.setString(1, bundleId);
            stmt.setInt(2, toDelete);
            int deleted = stmt.executeUpdate();
            logger.info("Event cap enforced for bundle {}: deleted {} oldest events to fit cap {}", bundleId, deleted, maxEventsPerBundle);
        }
    }

    private static int countOne(Connection connection, String countSql) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(countSql);
             ResultSet rs = stmt.executeQuery()) {
            checkState(rs.next(), "count(*) returned no row");
            return rs.getInt(1);
        }
    }

    private static AdminAlertData augmentWithFrameworkLabels(AdminAlertData data) {
        if (data.labels().containsKey(PID_LABEL)) {
            logger.info("Caller supplied '{}' label on alert raise — overwriting with framework value", PID_LABEL);
        }
        var augmented = ImmutableMap.<String, String>builder()
                                    .putAll(data.labels())
                                    .put(PID_LABEL, String.valueOf(PID))
                                    .buildKeepingLast();
        return data.withLabels(augmented);
    }

    private Optional<String> doResolve(String key, String note) {
        Instant now = timeProvider.currentInstant();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(resolveByDedupKeySql)) {
            stmt.setTimestamp(1, Timestamp.from(now));
            stmt.setString(2, "system");
            stmt.setString(3, note);
            stmt.setString(4, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve alert with key " + key, e);
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
                                               .setKey(rs.getString("dedup_key"))
                                               .setTitle(rs.getString("title"))
                                               .setSeverity(AdminAlertSeverity.valueOf(rs.getString("severity")))
                                               .setLabels(deserialiseLabels(rs.getString("labels")))
                                               .setFirstSeenAt(rs.getTimestamp("first_seen_at").toInstant())
                                               .setLastSeenAt(rs.getTimestamp("last_seen_at").toInstant())
                                               .setEventCount(rs.getInt("event_count"));
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

    private static void validateKey(String key) {
        checkNotNull(key, "key");
        checkArgument(!key.isBlank(), "key must not be blank");
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
