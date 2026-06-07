package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.recording.PostgresqlDestination.Column;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlDestinationImplTest {
    private static final String CREATE_AUX_TABLE_SQL = "CREATE TABLE IF NOT EXISTS %TABLE_NAME%_init (id integer);";
    private static final String ADD_AUX_NOTE_COLUMN_SQL = "ALTER TABLE %TABLE_NAME%_init ADD COLUMN note text;";
    private static final String QUERY_TEMPLATE =
            "SELECT %TIMESTAMP%, label, amount FROM %TABLE_NAME% WHERE %USER_CONDITION% ORDER BY %TIMESTAMP%";
    private static final String SELECT_TABLE_EXISTS_SQL =
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name=?";
    private static final String SELECT_COLUMN_EXISTS_SQL =
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?";
    private static final String SELECT_INDEX_EXISTS_SQL =
            "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname=?";
    private static final String SAMPLE_TABLE_NAME = "recorder_data_sample";
    private static final String SAMPLE_AUX_TABLE_NAME = "recorder_data_sample_init";
    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private DataSource dataSource;
    private SingleThreadedSchedulingExecutor domainExecutor;
    private SingleThreadedSchedulingExecutor recordingExecutor;
    private PersistenceDomainServiceImpl domainService;
    private PostgresqlDestinationImpl destination;
    private DataSourceFactory dataSourceFactory;

    @BeforeEach
    void setUp() {
        dataSource = postgres.dataSource();
        dataSourceFactory = postgres.dataSourceFactory();
        domainExecutor = new SingleThreadedSchedulingExecutor("domain-test");
        recordingExecutor = new SingleThreadedSchedulingExecutor("recording-test");
        Provider<SchedulingExecutor> domainExecutorProvider = () -> domainExecutor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, domainExecutorProvider);
        domainService.start();
        initDestination();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(
                destination,
                () -> {
                    if (recordingExecutor != null) {
                        flushExecutor(recordingExecutor);
                    }
                }, () -> {
                    if (domainService != null) {
                        domainService.stop();
                    }
                },
                recordingExecutor,
                domainExecutor);
    }

    static Stream<Optional<String>> recordsAndReadsRows() {
        return Stream.of(Optional.empty(), Optional.of("userId"));
    }

    @ParameterizedTest
    @MethodSource
    void recordsAndReadsRows(Optional<String> userId) throws Exception {
        var config = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                            "sample",
                                                            1,
                                                            List.of(CREATE_AUX_TABLE_SQL),
                                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                            sampleColumns());
        var recorder = destination.createRecorder(config, userId);
        var reader = destination.createReader(config, userId);

        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(tableExists(dataSource, SAMPLE_TABLE_NAME)).isTrue();
        assertThat(tableExists(dataSource, SAMPLE_AUX_TABLE_NAME)).isTrue();

        var start = Instant.parse("2024-01-01T00:00:00Z");
        recorder.record(start, new SampleRecord("first", 1));
        recorder.record(start, new SampleRecord("first", 1));
        recorder.record(start.plusSeconds(5), new SampleRecord("second", 2));
        flushExecutor(recordingExecutor);

        var rows = new ArrayList<SampleRow>();
        reader.query(QUERY_TEMPLATE,
                     _ -> {
                     },
                     row -> {
                         var timestamp = row.timestampReader().get();
                         var label = row.rs().getString(2);
                         var amount = row.rs().getInt(3);
                         rows.add(new SampleRow(timestamp, label, amount));
                     })
              .get(5, SECONDS);

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().label()).isEqualTo("first");
        assertThat(rows.getFirst().amount()).isEqualTo(1);
        assertThat(rows.get(1).label()).isEqualTo("second");
        assertThat(rows.get(1).amount()).isEqualTo(2);
    }

    @Test
    void deleterRemovesRowsForGivenUserOnly() throws Exception {
        var config = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                            "sample",
                                                            1,
                                                            List.of(),
                                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                            sampleColumns());
        var recorder = destination.createRecorder(config, Optional.of("userId"));
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        var start = Instant.parse("2024-01-01T00:00:00Z");
        recorder.record(start, new SampleRecord("a", 1));
        recorder.record(start.plusSeconds(5), new SampleRecord("b", 2));
        flushExecutor(recordingExecutor);

        // another user's row in the same table must be preserved
        insertRow(dataSource, "other", "keep", 9);

        // the deleter targets the given user ("userId"), leaving "other" untouched
        var deleter = destination.createDeleter(config, Optional.of("userId"));
        int deleted = deleter.delete("DELETE FROM %TABLE_NAME% WHERE %USER_CONDITION%").get(5, SECONDS);

        assertThat(deleted).isEqualTo(2);
        assertThat(rowCount(dataSource, "userId")).isZero();
        assertThat(rowCount(dataSource, "other")).isEqualTo(1);
    }

    @Test
    void deleterTreatsMissingTableAsNoRows() throws Exception {
        var config = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                            "neverrecorded",
                                                            1,
                                                            List.of(),
                                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                            sampleColumns());
        var deleter = destination.createDeleter(config, Optional.of("userId"));

        int deleted = deleter.delete("DELETE FROM %TABLE_NAME% WHERE %USER_CONDITION%").get(5, SECONDS);

        assertThat(deleted).isZero();
    }

    @Test
    void runsPostInitStatementsOnFreshInstallOnly() throws Exception {
        var postInit = "CREATE INDEX " + SAMPLE_TABLE_NAME + "_label_idx ON %TABLE_NAME% (label);";
        var configV1 = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                              "sample",
                                                              1,
                                                              List.of(),
                                                              List.of(postInit),
                                                              PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                              sampleColumns());
        destination.createRecorder(configV1, Optional.of("userId"));
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(indexExists(dataSource, SAMPLE_TABLE_NAME + "_label_idx")).isTrue();

        // Recreating the recorder against an already-initialised domain must NOT re-run post-init (which would fail on duplicate index).
        destination.createRecorder(configV1, Optional.of("userId"));
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(indexExists(dataSource, SAMPLE_TABLE_NAME + "_label_idx")).isTrue();
    }

    @Test
    void appliesMigrationStatements() throws Exception {
        var configV1 = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                              "sample",
                                                              1,
                                                              List.of(CREATE_AUX_TABLE_SQL),
                                                              PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                              sampleColumns());
        destination.createRecorder(configV1, Optional.of("userId"));
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(columnExists(dataSource, SAMPLE_AUX_TABLE_NAME, "note")).isFalse();

        PersistenceDomainMigrator migrator = toVersion -> toVersion == 2 ? List.of(ADD_AUX_NOTE_COLUMN_SQL) : List.of();
        var configV2 = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                              "sample",
                                                              2,
                                                              List.of(CREATE_AUX_TABLE_SQL),
                                                              migrator,
                                                              sampleColumns());
        destination.createRecorder(configV2, Optional.of("userId"));
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(columnExists(dataSource, SAMPLE_AUX_TABLE_NAME, "note")).isTrue();
    }

    private void initDestination() {
        Provider<SchedulingExecutor> recordingExecutorProvider = () -> recordingExecutor;
        destination = new PostgresqlDestinationImpl(recordingExecutorProvider, dataSourceFactory, domainService);
        destination.initialise();
        flushExecutor(recordingExecutor);
    }

    private static boolean tableExists(DataSource dataSource, String tableName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_TABLE_EXISTS_SQL)) {
            statement.setString(1, tableName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean indexExists(DataSource dataSource, String indexName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_INDEX_EXISTS_SQL)) {
            statement.setString(1, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean columnExists(DataSource dataSource, String tableName, String columnName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_COLUMN_EXISTS_SQL)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void insertRow(DataSource dataSource, String userId, String label, int amount) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + SAMPLE_TABLE_NAME + " (user_id, timestamp, label, amount) VALUES (?, now(), ?, ?)")) {
            statement.setString(1, userId);
            statement.setString(2, label);
            statement.setInt(3, amount);
            statement.executeUpdate();
        }
    }

    private static int rowCount(DataSource dataSource, String userId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + SAMPLE_TABLE_NAME + " WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
    }

    private static List<Column<SampleRecord, ?>> sampleColumns() {
        return List.of(new Column<>("label",
                                    "text",
                                    false,
                                    "?",
                                    input -> input.stmt().setString(input.colIdx(), input.record().label())),
                       new Column<>("amount",
                                    "integer",
                                    false,
                                    "?",
                                    input -> input.stmt().setInt(input.colIdx(), input.record().amount())));
    }

    private static void flushExecutor(SingleThreadedSchedulingExecutor executor) {
        MoreThrowables.asUnchecked(() -> executor.submit(() -> null).get(5, SECONDS));
    }

    private record SampleRecord(String label, int amount) {
        private SampleRecord {
            checkNotNull(label, "label");
        }
    }

    private record SampleRow(Instant timestamp, String label, int amount) {
        private SampleRow {
            checkNotNull(timestamp, "timestamp");
            checkNotNull(label, "label");
        }
    }
}
