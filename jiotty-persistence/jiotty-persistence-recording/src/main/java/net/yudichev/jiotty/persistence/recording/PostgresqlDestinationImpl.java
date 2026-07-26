package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ThrowingConsumer;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;
import net.yudichev.jiotty.persistence.recording.RecordingModule.PsqlExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.persistence.recording.RecordingModule.Dependency;

// LoggingSimilarMessage — every SQL-execution helper logs the resolved statement with the same "Executing {}" debug line by convention
@SuppressWarnings({"JDBCPrepareStatementWithNonConstantString", "JDBCExecuteWithNonConstantString", "LoggingSimilarMessage"})
class PostgresqlDestinationImpl extends BaseIdempotentCloseable implements PostgresqlDestination {
    private static final Logger logger = LogManager.getLogger(PostgresqlDestinationImpl.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final Calendar calendar;
    private final DataSourceFactory dataSourceFactory;
    private final PersistenceDomainService persistenceDomainService;
    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public PostgresqlDestinationImpl(@PsqlExecutor Provider<SchedulingExecutor> executorProvider,
                                     @Dependency DataSourceFactory dataSourceFactory,
                                     PersistenceDomainService persistenceDomainService) {
        this.executorProvider = checkNotNull(executorProvider);
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        this.persistenceDomainService = checkNotNull(persistenceDomainService);
        calendar = utcCalendar();
    }

    private static Calendar utcCalendar() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
        return calendar;
    }

    @Override
    public void initialise() {
        executor = executorProvider.get();
        executor.execute(this::connect);
    }

    @Override
    public <R> Recorder<R> createRecorder(Config<R> destinationConfig, Optional<String> userId) {
        var psqlConfig = (PsqlConfig<R>) destinationConfig;
        var recorder = new RecorderImpl<>(psqlConfig, userId.orElse(null));
        executor.execute(recorder::initialise);
        return recorder;
    }

    @Override
    public <R> Reader createReader(Config<R> destinationConfig, Optional<String> userId) {
        return new ReaderImpl<>((PsqlConfig<R>) destinationConfig, userId.orElse(null));
    }

    @Override
    public <R> Deleter createDeleter(Config<R> destinationConfig, Optional<String> userId) {
        return new DeleterImpl<>((PsqlConfig<R>) destinationConfig, userId.orElse(null));
    }

    @Override
    protected void doClose() {
        executor.execute(() -> Closeable.closeSafelyIfNotNull(logger, dataSource));
    }

    private void connect() {
        dataSource = dataSourceFactory.create("recording");
    }

    private static class SqlBase<R> {
        /// Rows fetched per server round-trip when streaming a query result. Bounds client memory: with a positive fetch size (and autoCommit off) pgjdbc
        ///  streams the result set in batches of this many rows instead of buffering the whole thing — see [#doQuery].
        protected static final int STREAM_FETCH_SIZE = 1000;
        protected static final String TIMESTAMP_COL_NAME = "timestamp";
        protected static final String USER_ID_COL_NAME = "user_id";
        protected static final Pattern TABLE_NAME_PATTERN = Pattern.compile("%TABLE_NAME%");
        protected static final Pattern TIMESTAMP_PATTERN = Pattern.compile("%TIMESTAMP%");
        protected static final Pattern USER_ID_CONDITION_PATTERN = Pattern.compile("%USER_CONDITION%");
        protected final PsqlConfig<R> config;
        protected final String typeName;
        protected final String tableName;
        protected final @Nullable String userId;

        protected SqlBase(PsqlConfig<R> config, @Nullable String userId) {
            this.config = checkNotNull(config);
            this.userId = userId;
            typeName = config.typeId();
            tableName = "recorder_data_" + config.domainConfig().domain().name(); // not using domain prefix for historical reasons
        }

        /// Resolves a SQL template for `userId`, substituting `%TABLE_NAME%` with the recorder table, `%TIMESTAMP%` with the timestamp column, and
        /// `%USER_CONDITION%` with the predicate selecting that user's rows (or the NULL-user rows when no user is given).
        protected final String resolveSql(String template) {
            var sql = TABLE_NAME_PATTERN.matcher(template).replaceAll(tableName);
            sql = TIMESTAMP_PATTERN.matcher(sql).replaceAll(TIMESTAMP_COL_NAME);
            return USER_ID_CONDITION_PATTERN.matcher(sql).replaceAll(USER_ID_COL_NAME + (userId == null ? " IS NULL" : "='" + userId + '\''));
        }

        protected static void execute(Connection connection, String sql) throws SQLException {
            try (var statement = connection.createStatement()) {
                logger.debug("Executing {}", sql);
                statement.execute(sql);
            }
        }

        protected static void doQuery(Connection connection,
                                      String sql,
                                      ThrowingConsumer<PreparedStatement, SQLException> paramSetter,
                                      ThrowingConsumer<ResultSet, SQLException> resultMapper) {
            try {
                boolean originalAutoCommit = connection.getAutoCommit();
                // pgjdbc streams a result set in STREAM_FETCH_SIZE-row batches only when autoCommit is off and a positive fetch size is set; otherwise it
                //  buffers the entire result client-side. The query is read-only — we commit purely to close the server-side cursor — and always restore
                //  autoCommit so the pooled connection returns to its prior mode.
                connection.setAutoCommit(false);
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setFetchSize(STREAM_FETCH_SIZE);
                    paramSetter.accept(stmt);
                    logger.debug("Executing {}", sql);
                    try (var resultSet = stmt.executeQuery()) {
                        resultMapper.accept(resultSet);
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(originalAutoCommit);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class RecorderImpl<R> extends SqlBase<R> implements Recorder<R> {
        private final String creatTableSql;
        private final String insertSql;

        private boolean disabled;
        private R lastRecorded;

        public RecorderImpl(PsqlConfig<R> config, @Nullable String userId) {
            super(config, userId);
            String columnNames = USER_ID_COL_NAME + ", " + TIMESTAMP_COL_NAME + ", " + this.config.columns().stream().map(Column::name).collect(joining(", "));
            String insertPlaceholders = this.config.columns().stream().map(Column::valuePlaceholder).collect(joining(", "));
            String columnsWithTypes = this.config.columns()
                                                 .stream()
                                                 .map(column -> column.name() + ' ' + column.sqlType() + (column.nullable() ? "" : " NOT NULL"))
                                                 .collect(joining(", "));
            creatTableSql = "CREATE TABLE IF NOT EXISTS " + tableName +
                            " (id serial, " + USER_ID_COL_NAME + " text, " + TIMESTAMP_COL_NAME + " timestamptz, " + columnsWithTypes + ");";
            insertSql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (?, ?, " + insertPlaceholders + ");";
        }

        public void initialise() {
            // Must only schedule a single task as the recorder can be used by users immediately after creation
            var domainConfig = withTableNamePlaceholders(config.domainConfig());
            var postInitStatements = replaceTableNamePlaceholders(config.postInitStatements());
            // persistenceDomainService is configured to use the same executor as this component
            persistenceDomainService.ensureDomainReady(domainConfig)
                                    .thenAccept(freshlyInitialised -> {
                                        ensureRecorderTableExists();
                                        if (freshlyInitialised) {
                                            executePostInitStatements(postInitStatements);
                                        }
                                    })
                                    .exceptionally(e -> {
                                        logger.warn("Initialisation of record for type {} with config {} failed, recording will be disabled",
                                                    typeName, config, e);
                                        disabled = true;
                                        return null;
                                    });
        }

        private void executePostInitStatements(List<String> postInitStatements) {
            if (postInitStatements.isEmpty()) {
                return;
            }
            asUnchecked(() -> {
                try (var connection = dataSource.getConnection()) {
                    for (String sql : postInitStatements) {
                        execute(connection, sql);
                    }
                }
            });
        }

        private PersistenceDomainConfig withTableNamePlaceholders(PersistenceDomainConfig base) {
            var initStatements = replaceTableNamePlaceholders(base.initStatements());
            PersistenceDomainMigrator migrator = toVersion -> replaceTableNamePlaceholders(base.migrator().getMigrationStatements(toVersion));
            return new PersistenceDomainConfig(base.domain(), base.schemaVersion(), initStatements, migrator);
        }

        private List<String> replaceTableNamePlaceholders(List<String> statements) {
            return statements.stream()
                             .map(sql -> TABLE_NAME_PATTERN.matcher(sql).replaceAll(tableName))
                             .toList();
        }

        private void ensureRecorderTableExists() {
            asUnchecked(() -> {
                try (var connection = dataSource.getConnection()) {
                    execute(connection, creatTableSql);
                }
            });
        }

        @Override
        public void record(DestinationType destinationType, Instant timestamp, R recordable) {
            if (destinationType == config.destinationType()) {
                record(timestamp, recordable);
            }
        }

        @Override
        public void record(Instant timestamp, R recordable) {
            executor.execute(() -> {
                if (disabled) {
                    logger.debug("Recording disabled, not recording: {}", recordable);
                    return;
                }
                if (!Objects.equals(lastRecorded, recordable)) {
                    try (var connection = dataSource.getConnection()) {
                        doUpdate(connection, insertSql, 1,
                                 stmt -> {
                                     int colIdx = 1;
                                     stmt.setString(colIdx++, userId);
                                     stmt.setTimestamp(colIdx++, Timestamp.from(timestamp), calendar);
                                     for (int i = 0; i < config.columns().size(); i++) {
                                         Column<R, ?> col = config.columns().get(i);
                                         col.stmtColValueSetter().set(new InsertStmtColValueSetter.Input<>(recordable, calendar, connection, stmt, colIdx++));
                                     }
                                 });
                        lastRecorded = recordable;
                    } catch (SQLException e) {
                        logger.warn("Failed recording {}, sql was {}", recordable, insertSql, e);
                    }
                }
            });
        }

        private static void doUpdate(Connection connection,
                                     String sql,
                                     int expectedRowsUpdated,
                                     ThrowingConsumer<PreparedStatement, SQLException> paramSetter) {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                paramSetter.accept(stmt);
                logger.debug("Executing {}", sql);
                var rows = stmt.executeUpdate();
                checkState(rows == expectedRowsUpdated, "rows updated expected %s but was %s in %s", expectedRowsUpdated, rows, sql);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class ReaderImpl<R> extends SqlBase<R> implements Reader {
        public ReaderImpl(PsqlConfig<R> config, @Nullable String userId) {
            super(config, userId);
        }

        @Override
        public CompletableFuture<Void> query(String queryTemplate,
                                             QueryStmtParamValueSetter paramValueSetter,
                                             ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler) {
            return query(executor, queryTemplate, paramValueSetter, rowHandler);
        }

        @Override
        public CompletableFuture<Void> query(Executor queryExecutor,
                                             String queryTemplate,
                                             QueryStmtParamValueSetter paramValueSetter,
                                             ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler) {
            return CompletableFuture.runAsync(() -> {
                // The shared `calendar` is safe to reuse only when the query runs on the recording executor, where it is already confined alongside the
                //  recorder. On any other executor the recorder keeps mutating it concurrently and java.util.Calendar is not thread-safe, so use a fresh one.
                Calendar queryCalendar = queryExecutor == executor ? calendar : utcCalendar();
                var sql = resolveSql(queryTemplate);
                try (var connection = dataSource.getConnection()) {
                    doQuery(connection,
                            sql,
                            ps -> paramValueSetter.set(new Reader.QueryStmtParamValueSetter.Input(queryCalendar, connection, ps)),
                            rs -> {
                                while (rs.next()) {
                                    rowHandler.accept(new Reader.QueryResultRow(queryCalendar, connection, rs,
                                                                                () -> rs.getTimestamp(1, queryCalendar).toInstant()));
                                }
                            });
                } catch (SQLException e) {
                    logger.warn("Failed executing query, sql was {}", sql, e);
                }
            }, queryExecutor);
        }
    }

    private class DeleterImpl<R> extends SqlBase<R> implements Deleter {
        DeleterImpl(PsqlConfig<R> config, @Nullable String userId) {
            super(config, userId);
        }

        @Override
        public CompletableFuture<Integer> delete(String deleteTemplate) {
            return executor.submit(() -> {
                var sql = resolveSql(deleteTemplate);
                try (var connection = dataSource.getConnection();
                     var statement = connection.createStatement()) {
                    logger.debug("Executing {}", sql);
                    return statement.executeUpdate(sql);
                } catch (SQLException e) {
                    // undefined_table: nothing was ever recorded for this type, so there is nothing to delete
                    if ("42P01".equals(e.getSQLState())) {
                        logger.debug("Table {} does not exist; nothing to delete", tableName);
                        return 0;
                    }
                    throw new RuntimeException("Failed deleting recorded data from " + tableName, e);
                }
            });
        }
    }
}