package net.yudichev.jiotty.persistence.recording;

import jakarta.annotation.Nullable;
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
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.persistence.recording.RecordingModule.Dependency;

@SuppressWarnings({"JDBCPrepareStatementWithNonConstantString", "JDBCExecuteWithNonConstantString"})
class PostgresqlDestinationImpl extends BaseIdempotentCloseable implements PostgresqlDestination {
    private static final Logger logger = LogManager.getLogger(PostgresqlDestinationImpl.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final Calendar calendar;
    private final DataSourceFactory dataSourceFactory;
    private final PersistenceDomainService persistenceDomainService;
    private final @Nullable String userId;
    private SchedulingExecutor executor;
    private CloseableDataSource dataSource;

    @Inject
    public PostgresqlDestinationImpl(@PsqlExecutor Provider<SchedulingExecutor> executorProvider,
                                     @Dependency DataSourceFactory dataSourceFactory,
                                     @Dependency Optional<String> userId,
                                     PersistenceDomainService persistenceDomainService) {
        this.executorProvider = checkNotNull(executorProvider);
        this.dataSourceFactory = checkNotNull(dataSourceFactory);
        this.userId = userId.orElse(null);
        this.persistenceDomainService = checkNotNull(persistenceDomainService);
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
        protected static final String USER_ID_COL_NAME = "user_id";
        protected final PsqlConfig<R> config;
        protected final String typeName;
        protected final String tableName;

        protected SqlBase(PsqlConfig<R> config) {
            this.config = checkNotNull(config);
            typeName = config.typeId();
            tableName = "recorder_data_" + config.domainConfig().domain().name(); // not using domain prefix for historical reasons
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
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                paramSetter.accept(stmt);
                logger.debug("Executing {}", sql);
                try (var resultSet = stmt.executeQuery()) {
                    resultMapper.accept(resultSet);
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private class RecorderImpl<R> extends SqlBase<R> implements Recorder<R> {
        protected static final Pattern TABLE_NAME_PATTERN = Pattern.compile("%TABLE_NAME%");

        private final String creatTableSql;
        private final String insertSql;

        private boolean disabled;
        private R lastRecorded;

        public RecorderImpl(PsqlConfig<R> config) {
            super(config);
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
            // persistenceDomainService is configured to use the same executor as this component
            persistenceDomainService.ensureDomainReady(domainConfig)
                                    .thenRun(this::ensureRecorderTableExists)
                                    .exceptionally(e -> {
                                        logger.warn("Initialisation of record for type {} with config {} failed, recording will be disabled",
                                                    typeName, config, e);
                                        disabled = true;
                                        return null;
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
        protected static final Pattern TIMESTAMP_PATTERN = Pattern.compile("%TIMESTAMP%");
        protected static final Pattern USER_ID_CONDITION_PATTERN = Pattern.compile("%USER_CONDITION%");

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
                sql = USER_ID_CONDITION_PATTERN.matcher(sql).replaceAll(USER_ID_COL_NAME + (userId == null ? " IS NULL" : "='" + userId + '\''));
                try (var connection = dataSource.getConnection()) {
                    doQuery(connection,
                            sql,
                            ps -> paramValueSetter.set(new Reader.QueryStmtParamValueSetter.Input(calendar, connection, ps)),
                            rs -> {
                                while (rs.next()) {
                                    rowHandler.accept(new Reader.QueryResultRow(calendar, connection, rs, () -> rs.getTimestamp(1, calendar).toInstant()));
                                }
                            });
                } catch (SQLException e) {
                    logger.warn("Failed executing query, sql was {}", sql, e);
                }
            });
        }
    }
}