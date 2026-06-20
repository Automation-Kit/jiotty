package net.yudichev.jiotty.persistence.varstore;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.persistence.varstore.Bindings.SingleUser;
import static net.yudichev.jiotty.persistence.varstore.Bindings.ThePath;

public final class SqlVarStore extends BaseLifecycleComponent implements VarStore {
    /// Thread-name base of the single-threaded executor this store's persistence work runs on.
    @VisibleForTesting
    public static final String EXECUTOR_THREAD_NAME = "varstore-sql-test";

    private static final Logger logger = LogManager.getLogger(SqlVarStore.class);

    private final String tableName;
    private final boolean singleUser;
    private final ExecutorFactory executorFactory;
    private final DataSourceFactory dataSourceFactory;
    private final String createTableSql;
    private final String upsertSql;
    private final String deleteSql;
    private final String deleteAllSql;
    private final String selectAllSql;
    private final Optional<Path> legacyPath;
    private final Optional<VarStoreEncryption> encryption;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;
    private SqlVarStoreOperations operations;

    @Inject
    SqlVarStore(@Dependency DataSourceFactory dataSourceFactory,
                ExecutorFactory executorFactory,
                @TableName String tableName,
                @SingleUser boolean singleUser,
                @ThePath Optional<Path> legacyPath,
                Optional<VarStoreEncryption> encryption) {
        this.tableName = checkNotNull(tableName, "tableName");
        // prevent SQL injection
        //noinspection DynamicRegexReplaceableByCompiledPattern
        checkArgument(tableName.matches("[a-zA-Z0-9_-]+"), "Illegal table name '%s'", tableName);
        upsertSql = createUpsertSql(tableName);
        deleteSql = createDeleteSql(tableName);
        deleteAllSql = createDeleteAllSql(tableName);
        selectAllSql = createSelectAllSql(tableName);
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        createTableSql = createCreateTableSql(tableName);
        this.executorFactory = checkNotNull(executorFactory);
        this.singleUser = singleUser;
        this.legacyPath = checkNotNull(legacyPath);
        this.encryption = checkNotNull(encryption);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(EXECUTOR_THREAD_NAME);
        dataSource = dataSourceFactory.create();
        operations = new SqlVarStoreOperations(dataSource, executor, "", upsertSql, deleteSql, deleteAllSql, selectAllSql, encryption.orElse(null));
        createTableIfNeeded();
        legacyPath.ifPresent(path -> FileToSqlVarStoreMigrator.migrate(path, this));
        operations.loadAll();
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, dataSource, executor);
    }

    private void createTableIfNeeded() {
        asUnchecked(() -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute(createTableSql);
            }
        });
        logger.info("Ensured table '{}' exists", tableName);
    }

    @Override
    public void saveValue(String key, Object value) {
        whenStartedAndNotLifecycling(() -> operations.saveValue(key, value));
    }

    @Override
    public void saveValueEncrypted(String key, Object value) {
        whenStartedAndNotLifecycling(() -> operations.saveValueEncrypted(key, value));
    }

    @Override
    public void clearValue(String key) {
        whenStartedAndNotLifecycling(() -> operations.clearValue(key));
    }

    @Override
    public void clearAll() {
        whenStartedAndNotLifecycling(() -> {
            checkState(singleUser, "clearAll() on the unscoped multi-user store is not supported; use forUser(userId).clearAll()");
            operations.clearAll();
        });
    }

    @Override
    public List<ExportedEntry> exportEntries() {
        return whenStartedAndNotLifecycling(() -> {
            checkState(singleUser, "exportEntries() on the unscoped multi-user store is not supported; use forUser(userId).exportEntries()");
            return operations.exportEntries();
        });
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return whenStartedAndNotLifecycling(() -> operations.readValue(type, key));
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        return whenStartedAndNotLifecycling(() -> operations.readValueEncrypted(type, key));
    }

    @Override
    public VarStore forUser(String userId) {
        return whenStartedAndNotLifecycling(() -> {
            if (singleUser) {
                checkArgument(userId.isEmpty(), "In single-user mode, userId must be empty but was: %s", userId);
                return this;
            }
            return new UserSqlVarStore(userId);
        });
    }

    CloseableDataSource dataSource() {
        return dataSource;
    }

    SchedulingExecutor executor() {
        return executor;
    }

    String tableName() {
        return tableName;
    }

    private static String createCreateTableSql(String tableName) {
        return "CREATE TABLE IF NOT EXISTS " + tableName + " ("
               + "user_id TEXT NOT NULL DEFAULT '',"
               + "key TEXT NOT NULL,"
               + "value TEXT NOT NULL,"
               + "create_time TIMESTAMPTZ NOT NULL,"
               + "update_time TIMESTAMPTZ NOT NULL,"
               + "PRIMARY KEY (user_id, key)"
               + ")";
    }

    private static String createUpsertSql(String tableName) {
        return "INSERT INTO " + tableName + " (user_id, key, value, create_time, update_time) VALUES (?, ?, ?, ?, ?)"
               + " ON CONFLICT (user_id, key) DO UPDATE SET value = EXCLUDED.value, update_time = ?";
    }

    private static String createDeleteSql(String tableName) {
        return "DELETE FROM " + tableName + " WHERE user_id = ? AND key = ?";
    }

    private static String createDeleteAllSql(String tableName) {
        return "DELETE FROM " + tableName + " WHERE user_id = ?";
    }

    private static String createSelectAllSql(String tableName) {
        return "SELECT key, value FROM " + tableName + " WHERE user_id = ?";
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface TableName {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    private class UserSqlVarStore implements VarStore {
        private final SqlVarStoreOperations userOperations;

        public UserSqlVarStore(String userId) {
            Utils.validateUserId(userId);
            userOperations = new SqlVarStoreOperations(dataSource, executor, userId, upsertSql, deleteSql, deleteAllSql, selectAllSql, encryption.orElse(null));
            userOperations.loadAll();
        }

        @Override
        public VarStore forUser(String userId) {
            throw new IllegalStateException("VarStore is already scoped to a user");
        }

        @Override
        public void saveValue(String key, Object value) {
            whenStartedAndNotLifecycling(() -> userOperations.saveValue(key, value));
        }

        @Override
        public void saveValueEncrypted(String key, Object value) {
            whenStartedAndNotLifecycling(() -> userOperations.saveValueEncrypted(key, value));
        }

        @Override
        public void clearValue(String key) {
            whenStartedAndNotLifecycling(() -> userOperations.clearValue(key));
        }

        @Override
        public void clearAll() {
            whenStartedAndNotLifecycling(userOperations::clearAll);
        }

        @Override
        public List<ExportedEntry> exportEntries() {
            return whenStartedAndNotLifecycling(userOperations::exportEntries);
        }

        @Override
        public <T> Optional<T> readValue(TypeToken<T> type, String key) {
            return whenStartedAndNotLifecycling(() -> userOperations.readValue(type, key));
        }

        @Override
        public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
            return whenStartedAndNotLifecycling(() -> userOperations.readValueEncrypted(type, key));
        }
    }
}
