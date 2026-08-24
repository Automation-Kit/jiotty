package net.yudichev.jiotty.persistence.domain;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.persistence.domain.PersistenceDomainModule.Dependency;

// LoggingSimilarMessage — every SQL-execution helper logs the resolved statement with the same "Executing" debug line by convention
@SuppressWarnings({"JDBCPrepareStatementWithNonConstantString", "LoggingSimilarMessage"})
public final class PersistenceDomainServiceImpl extends BaseLifecycleComponent implements PersistenceDomainService {
    private static final Logger logger = LogManager.getLogger(PersistenceDomainServiceImpl.class);
    private static final String META_TABLE = "domain_meta";
    private static final String CREATE_META_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS " + META_TABLE + " (domain_name text primary key, schema_version integer);";
    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext(?));";
    private static final String SELECT_VERSION_SQL = "SELECT schema_version FROM " + META_TABLE + " WHERE domain_name=?";
    private static final String INSERT_VERSION_SQL = "INSERT INTO " + META_TABLE + " (domain_name, schema_version) VALUES (?,?)";
    private static final String UPDATE_VERSION_SQL = "UPDATE " + META_TABLE + " SET schema_version=? WHERE domain_name=?";

    private final DataSourceFactory dataSourceFactory;
    private final Provider<SchedulingExecutor> executorProvider;
    private final TaskFailureReporter taskFailureReporter;

    private CloseableDataSource dataSource;
    private SchedulingExecutor executor;

    @Inject
    public PersistenceDomainServiceImpl(@Dependency DataSourceFactory dataSourceFactory,
                                        @Dependency Provider<SchedulingExecutor> executorProvider,
                                        TaskFailureReporter taskFailureReporter) {
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        this.executorProvider = checkNotNull(executorProvider);
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    @Override
    public CompletableFuture<Boolean> ensureDomainReady(PersistenceDomainConfig config) {
        // A stopped/stopping service ignores the call by returning a future that never completes, so a task draining off the shared
        // executor during shutdown is dropped rather than throwing synchronously or running against a closed datasource.
        return whenNotLifecycling(() -> isStarted()
                                        ? executor.submit(() -> whenStartedAndNotLifecycling(() -> ensureDomainReadySync(config)))
                                        : new CompletableFuture<>());
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource);
    }

    private boolean ensureDomainReadySync(PersistenceDomainConfig config) {
        String domainName = config.domain().name();
        ensureConnected();
        boolean freshlyInitialised = false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, CREATE_META_TABLE_SQL);
                acquireAdvisoryLock(connection, domainName);
                Integer storageVersion = selectCurrentVersion(connection, domainName);
                int targetVersion = config.schemaVersion();
                logger.info("[{}] Schema version in storage {}, target {}", domainName, storageVersion, targetVersion);
                if (storageVersion == null) {
                    logger.info("[{}] Initialising persistence domain", domainName);
                    executeAll(connection, config, config.initStatements());
                    insertVersion(connection, domainName, targetVersion);
                    freshlyInitialised = true;
                } else if (storageVersion < targetVersion) {
                    migrate(connection, config, storageVersion, targetVersion);
                } else if (storageVersion > targetVersion) {
                    throw new UnsupportedOperationException("Domain " + domainName + " has newer schema version " + storageVersion
                                                            + " than target " + targetVersion);
                }
                connection.commit();
            } catch (@SuppressWarnings("OverlyBroadCatchBlock") Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    // The connection goes back to the pool mid-transaction, so this is escalated; the failure that caused the rollback is rethrown below and
                    // reported in its own right.
                    taskFailureReporter.onTaskException("rolling back schema migration of domain " + domainName, rollbackException);
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialise domain " + domainName, e);
        }
        return freshlyInitialised;
    }

    private static void migrate(Connection connection, PersistenceDomainConfig config, int storageVersion, int targetVersion) throws SQLException {
        String domainName = config.domain().name();
        for (int v = storageVersion + 1; v <= targetVersion; v++) {
            List<String> statements = config.migrator().getMigrationStatements(v);
            logger.info("[{}] Migrating schema from v{} to v{} ({} statements)", domainName, v - 1, v, statements.size());
            executeAll(connection, config, statements);
            updateVersion(connection, domainName, v);
        }
    }

    private void ensureConnected() {
        if (dataSource == null) {
            dataSource = dataSourceFactory.create();
        }
    }

    private static void executeAll(Connection connection, PersistenceDomainConfig config, List<String> statements) throws SQLException {
        for (String sql : statements) {
            String expanded = expandSql(config, sql);
            execute(connection, expanded);
        }
    }

    private static String expandSql(PersistenceDomainConfig config, String sql) {
        return sql.replace("%DOMAIN%", config.domain().name())
                  .replace("%DOMAIN_PREFIX%", config.domain().prefix());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            logger.debug("Executing {}", sql);
            statement.execute(sql);
        }
    }

    private static void acquireAdvisoryLock(Connection connection, String domainName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(LOCK_SQL)) {
            stmt.setString(1, domainName);
            logger.debug("Acquiring advisory lock for domain {}", domainName);
            stmt.execute();
        }
    }

    private static @Nullable Integer selectCurrentVersion(Connection connection, String domainName) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(SELECT_VERSION_SQL)) {
            stmt.setString(1, domainName);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private static void insertVersion(Connection connection, String domainName, int version) throws SQLException {
        doUpdate(connection, INSERT_VERSION_SQL, 1, stmt -> {
            stmt.setString(1, domainName);
            stmt.setInt(2, version);
        });
    }

    private static void updateVersion(Connection connection, String domainName, int version) throws SQLException {
        doUpdate(connection, UPDATE_VERSION_SQL, 1, stmt -> {
            stmt.setInt(1, version);
            stmt.setString(2, domainName);
        });
    }

    private static void doUpdate(Connection connection, String sql, int expectedRowsUpdated, SqlConsumer<PreparedStatement> paramSetter) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            paramSetter.accept(stmt);
            logger.debug("Executing update {}", sql);
            int rows = stmt.executeUpdate();
            checkState(rows == expectedRowsUpdated, "rows updated expected %s but was %s in %s", expectedRowsUpdated, rows, sql);
        }
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T target) throws SQLException;
    }
}
