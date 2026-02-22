package net.yudichev.jiotty.persistence.recording;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainServiceImpl;
import net.yudichev.jiotty.persistence.recording.PostgresqlDestination.Column;
import net.yudichev.jiotty.persistence.test.EmbeddedPostgresExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlDestinationImplTest {
    private static final String CREATE_AUX_TABLE_SQL = "CREATE TABLE IF NOT EXISTS %TABLE_NAME%_init (id integer);";
    private static final String ADD_AUX_NOTE_COLUMN_SQL = "ALTER TABLE %TABLE_NAME%_init ADD COLUMN note text;";
    private static final String QUERY_TEMPLATE = "SELECT %TIMESTAMP%, label, amount FROM %TABLE_NAME% ORDER BY %TIMESTAMP%";
    private static final String SELECT_TABLE_EXISTS_SQL =
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name=?";
    private static final String SELECT_COLUMN_EXISTS_SQL =
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?";
    private static final String SAMPLE_TABLE_NAME = "recorder_data_sample";
    private static final String SAMPLE_AUX_TABLE_NAME = "recorder_data_sample_init";
    @RegisterExtension
    private final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private DataSource dataSource;
    private SingleThreadedSchedulingExecutor domainExecutor;
    private SingleThreadedSchedulingExecutor recordingExecutor;
    private PersistenceDomainServiceImpl domainService;
    private PostgresqlDestinationImpl destination;

    @BeforeEach
    void setUp() {
        dataSource = postgres.dataSource();
        var dataSourceFactory = postgres.dataSourceFactory();
        domainExecutor = new SingleThreadedSchedulingExecutor("domain-test");
        recordingExecutor = new SingleThreadedSchedulingExecutor("recording-test");
        Provider<SchedulingExecutor> domainExecutorProvider = () -> domainExecutor;
        domainService = new PersistenceDomainServiceImpl(dataSourceFactory, domainExecutorProvider);
        domainService.start();
        Provider<SchedulingExecutor> recordingExecutorProvider = () -> recordingExecutor;
        destination = new PostgresqlDestinationImpl(recordingExecutorProvider, dataSourceFactory, domainService);
        destination.initialise();
        flushExecutor(recordingExecutor);
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

    @Test
    void recordsAndReadsRows() throws Exception {
        var config = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                            "sample",
                                                            1,
                                                            List.of(CREATE_AUX_TABLE_SQL),
                                                            PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                            sampleColumns());
        var recorder = destination.createRecorder(config);
        var reader = destination.createReader(config);

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
    void appliesMigrationStatements() throws Exception {
        var configV1 = new PostgresqlDestination.PsqlConfig<>(SampleRecord.class,
                                                              "sample",
                                                              1,
                                                              List.of(CREATE_AUX_TABLE_SQL),
                                                              PersistenceDomainMigrator.FAIL_ON_MIGRATION,
                                                              sampleColumns());
        destination.createRecorder(configV1);
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
        destination.createRecorder(configV2);
        flushExecutor(recordingExecutor);
        flushExecutor(domainExecutor);
        flushExecutor(recordingExecutor);

        assertThat(columnExists(dataSource, SAMPLE_AUX_TABLE_NAME, "note")).isTrue();
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
