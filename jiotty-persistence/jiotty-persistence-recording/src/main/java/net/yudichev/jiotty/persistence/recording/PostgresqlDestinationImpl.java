package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ThrowingConsumer;
import net.yudichev.jiotty.common.lang.ThrowingFunction;
import net.yudichev.jiotty.persistence.psql.CloseableDataSource;
import net.yudichev.jiotty.persistence.psql.PsqlDataSourceFactory;
import net.yudichev.jiotty.persistence.recording.RecordingModule.PsqlExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.persistence.recording.RecordingModule.Dependency;

@SuppressWarnings({"JDBCPrepareStatementWithNonConstantString", "JDBCExecuteWithNonConstantString"})
class PostgresqlDestinationImpl extends BaseIdempotentCloseable implements PostgresqlDestination {
    private static final Logger logger = LoggerFactory.getLogger(PostgresqlDestinationImpl.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final Calendar calendar;
    private final PsqlDataSourceFactory dataSourceFactory;

    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public PostgresqlDestinationImpl(@PsqlExecutor Provider<SchedulingExecutor> executorProvider,
                                     @Dependency PsqlDataSourceFactory dataSourceFactory) {
        this.executorProvider = checkNotNull(executorProvider);
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getTimeZone(ZoneOffset.UTC));
    }

    @Override
    public void initialise() {
        executor = executorProvider.get();
        executor.execute(this::connect);
    }

    @Override
    public <R> Recorder<R> createRecorder(Config<R> destinationConfig) {
        var psqlConfig = (PsqlConfig<R>) destinationConfig;
        var recorder = new RecorderImpl<>(psqlConfig);
        executor.execute(recorder::initialise);
        return recorder;
    }

    @Override
    public <R> Reader createReader(Config<R> destinationConfig) {
        return new ReaderImpl<>((PsqlConfig<R>) destinationConfig);
    }

    @Override
    protected void doClose() {
        executor.execute(() -> Closeable.closeSafelyIfNotNull(logger, dataSource));
    }

    private void connect() {
        dataSource = dataSourceFactory.create();
    }

    @SuppressWarnings("LoggingSimilarMessage")
    private static class SqlBase<R> {
        protected static final String TIMESTAMP_COL_NAME = "timestamp";
        protected final PsqlConfig<R> config;
        protected final String typeName;
        protected final String tableName;

        protected SqlBase(PsqlConfig<R> config) {
            this.config = checkNotNull(config);
            typeName = config.typeId();
            tableName = "recorder_data_" + typeName;
        }

        protected static void execute(Connection connection, String sql) throws SQLException {
            try (var statement = connection.createStatement()) {
                logger.debug("Executing {}", sql);
                statement.execute(sql);
            }
        }

        protected static <T> T doQuery(Connection connection,
                                       String sql,
                                       ThrowingConsumer<PreparedStatement, SQLException> paramSetter,
                                       ThrowingFunction<ResultSet, T, SQLException> resultMapper) {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                paramSetter.accept(stmt);
                logger.debug("Executing {}", sql);
                try (var resultSet = stmt.executeQuery()) {
                    return resultMapper.apply(resultSet);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class RecorderImpl<R> extends SqlBase<R> implements Recorder<R> {
        protected static final Pattern TABLE_NAME_PATTERN = Pattern.compile("%TABLE_NAME%");
        private final String columnNames;
        private final String columnsWithTypes;
        private final String insertPlaceholders;
        private boolean disabled;

        private R lastRecorded;

        public RecorderImpl(PsqlConfig<R> config) {
            super(config);
            columnsWithTypes = this.config.columns()
                                          .stream()
                                          .map(column -> column.name() + ' ' + column.sqlType() + (column.nullable() ? "" : " NOT NULL"))
                                          .collect(joining(", "));
            columnNames = TIMESTAMP_COL_NAME + ", " + this.config.columns().stream().map(Column::name).collect(joining(", "));
            insertPlaceholders = this.config.columns().stream().map(Column::valuePlaceholder).collect(joining(", "));
        }

        public void initialise() {
            try (var connection = dataSource.getConnection()) {
                execute(connection, "CREATE TABLE IF NOT EXISTS recorder_meta (type_name text, schemaVersion integer);");
                Integer storageSchemaVersion = doQuery(connection, "SELECT schemaVersion FROM recorder_meta WHERE type_name=?;",
                                                       stmt -> stmt.setString(1, typeName),
                                                       rs -> rs.next() ? rs.getInt(1) : null);
                logger.info("[{}] Schema version in storage {}, actual {}", typeName, storageSchemaVersion, config.schemaVersion());
                if (storageSchemaVersion == null) {
                    logger.info("[{}] Registering type and creating table", typeName);
                    for (String initStmt : config.initStatements()) {
                        execute(connection, initStmt);
                    }
                    execute(connection, "CREATE TABLE recorder_data_" + typeName +
                                        " (id serial, " + TIMESTAMP_COL_NAME + " timestamptz, " + columnsWithTypes + ");");
                    doUpdate(connection, "INSERT INTO recorder_meta (type_name, schemaVersion) VALUES (?,?);", 1,
                             input -> {
                                 input.setString(1, typeName);
                                 input.setInt(2, config.schemaVersion());
                             });
                } else if (storageSchemaVersion != config.schemaVersion()) {
                    if (storageSchemaVersion == config.schemaVersion() - 1) {
                        logger.info("Migrating schema from v{} to v{}", storageSchemaVersion, config.schemaVersion());
                        for (String sql : config.migrator().getMigrationStatements(config.schemaVersion())) {
                            execute(connection, TABLE_NAME_PATTERN.matcher(sql).replaceAll(tableName));
                        }
                        doUpdate(connection, "UPDATE recorder_meta SET schemaVersion=? WHERE type_name=?",
                                 1,
                                 input -> {
                                     input.setInt(1, config.schemaVersion());
                                     input.setString(2, typeName);
                                 });
                    } else {
                        //noinspection ThrowCaughtLocally
                        throw new UnsupportedOperationException("Dealing with complex schema version conflicts not supported: type " + typeName
                                                                + ", storage version " + storageSchemaVersion +
                                                                ", actual version " + config.schemaVersion());
                    }
                }
            } catch (@SuppressWarnings("OverlyBroadCatchBlock") Exception e) {
                logger.warn("Initialisation of record for type {} with config {} failed, recording will be disabled",
                            typeName, config, e);
                disabled = true;
            }
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
                    String sql = "INSERT INTO " + tableName + " (" + columnNames + ") VALUES (?, " + insertPlaceholders + ");";
                    try (var connection = dataSource.getConnection()) {
                        doUpdate(connection, sql, 1,
                                 stmt -> {
                                     stmt.setTimestamp(1, Timestamp.from(timestamp), calendar);
                                     for (int i = 0; i < config.columns().size(); i++) {
                                         Column<R, ?> col = config.columns().get(i);
                                         col.stmtColValueSetter().set(new InsertStmtColValueSetter.Input<>(recordable, calendar, connection, stmt, i + 2));
                                     }
                                 });
                        lastRecorded = recordable;
                    } catch (SQLException e) {
                        logger.warn("Failed recording {}, sql was {}", recordable, sql, e);
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
        protected static final Pattern TIMESTAMP_PATTERN = Pattern.compile("%TIMESTAMP%");

        public ReaderImpl(PsqlConfig<R> config) {
            super(config);
        }

        @Override
        public CompletableFuture<Void> query(String queryTemplate,
                                             QueryStmtParamValueSetter paramValueSetter,
                                             ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler) {
            return executor.submit(() -> {
                var sql = RecorderImpl.TABLE_NAME_PATTERN.matcher(queryTemplate).replaceAll(tableName);
                sql = TIMESTAMP_PATTERN.matcher(sql).replaceAll(TIMESTAMP_COL_NAME);
                try (var connection = dataSource.getConnection()) {
                    doQuery(connection,
                            sql,
                            ps -> paramValueSetter.set(new Reader.QueryStmtParamValueSetter.Input(calendar, connection, ps)),
                            rs -> {
                                while (rs.next()) {
                                    rowHandler.accept(new Reader.QueryResultRow(calendar, connection, rs, () -> rs.getTimestamp(1, calendar).toInstant()));
                                }
                                return null;
                            });
                } catch (SQLException e) {
                    logger.warn("Failed executing query, sql was {}", sql, e);
                }
            });
        }
    }
}