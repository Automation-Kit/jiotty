package net.yudichev.jiotty.persistence.domain;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceDomainServiceImplTest {
    private static final String CREATE_SAMPLE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%sample (id integer);";
    private static final String ADD_SAMPLE_NAME_COLUMN_SQL = "ALTER TABLE %DOMAIN_PREFIX%sample ADD COLUMN name text;";
    private static final String DELETE_META_ROW_SQL = "DELETE FROM domain_meta WHERE domain_name='%DOMAIN%';";
    private static final String SELECT_SCHEMA_VERSION_SQL = "SELECT schema_version FROM domain_meta WHERE domain_name=?";
    private static final String SELECT_TABLE_EXISTS_SQL =
            "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name=?";
    private static final String SELECT_COLUMN_EXISTS_SQL =
            "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?";
    @RegisterExtension
    private static final EmbeddedPostgresExtension postgres = new EmbeddedPostgresExtension();
    private DataSource dataSource;
    private SingleThreadedSchedulingExecutor executor;
    private PersistenceDomainServiceImpl service;

    @BeforeEach
    void setUp() {
        dataSource = postgres.dataSource();
        var dataSourceFactory = postgres.dataSourceFactory();
        executor = new SingleThreadedSchedulingExecutor("persistence-domain-test");
        Provider<SchedulingExecutor> executorProvider = () -> executor;
        service = new PersistenceDomainServiceImpl(dataSourceFactory, executorProvider);
        service.start();
    }

    @AfterEach
    void tearDown() {
        service.stop();
        executor.close();
    }

    @Test
    void initialisesAndRestartsAgainstSameDatabase() throws Exception {
        var domain = new PersistenceDomain("test_domain");
        var config = new PersistenceDomainConfig(domain,
                                                 1,
                                                 List.of(CREATE_SAMPLE_TABLE_SQL),
                                                 PersistenceDomainMigrator.FAIL_ON_MIGRATION);

        assertThat(service.ensureDomainReady(config).get(5, TimeUnit.SECONDS)).isTrue();
        assertDomainState(dataSource, domain);

        service.stop();
        service.start();
        assertThat(service.ensureDomainReady(config).get(5, TimeUnit.SECONDS)).isFalse();
        assertDomainState(dataSource, domain);
    }

    @Test
    void migratesForwardAndRejectsDowngrade() throws Exception {
        var domain = new PersistenceDomain("migration_domain");
        var v1Config = new PersistenceDomainConfig(domain,
                                                   1,
                                                   List.of(CREATE_SAMPLE_TABLE_SQL),
                                                   PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        var v2Config = new PersistenceDomainConfig(domain,
                                                   2,
                                                   List.of(CREATE_SAMPLE_TABLE_SQL),
                                                   toVersion -> toVersion == 2 ? List.of(ADD_SAMPLE_NAME_COLUMN_SQL) : List.of());

        assertThat(service.ensureDomainReady(v1Config).get(5, TimeUnit.SECONDS)).isTrue();
        assertDomainState(dataSource, domain);

        assertThat(service.ensureDomainReady(v2Config).get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(readSchemaVersion(dataSource, domain.name())).isEqualTo(2);
        assertThat(columnExists(dataSource, domain.prefix() + "sample", "name")).isTrue();

        var downgradeConfig = new PersistenceDomainConfig(domain,
                                                          1,
                                                          List.of(CREATE_SAMPLE_TABLE_SQL),
                                                          PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        assertThatThrownBy(() -> service.ensureDomainReady(downgradeConfig).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void whenStoppedIgnoresCallAndReturnsNeverCompletingFuture() {
        service.stop();
        var config = new PersistenceDomainConfig(new PersistenceDomain("stopped_domain"),
                                                 1,
                                                 List.of(CREATE_SAMPLE_TABLE_SQL),
                                                 PersistenceDomainMigrator.FAIL_ON_MIGRATION);

        // A stopped service ignores the call: the future is returned without throwing on the calling thread and never completes.
        var future = service.ensureDomainReady(config);

        assertThat(future).isNotDone();
        assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    }

    @Test
    void failsWhenDomainMetaRowMissingDuringMigration() throws Exception {
        var domain = new PersistenceDomain("missing_meta_domain");
        var v1Config = new PersistenceDomainConfig(domain,
                                                   1,
                                                   List.of(CREATE_SAMPLE_TABLE_SQL),
                                                   PersistenceDomainMigrator.FAIL_ON_MIGRATION);
        var v2Config = new PersistenceDomainConfig(domain,
                                                   2,
                                                   List.of(CREATE_SAMPLE_TABLE_SQL),
                                                   toVersion -> toVersion == 2 ? List.of(DELETE_META_ROW_SQL) : List.of());

        service.ensureDomainReady(v1Config).get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> service.ensureDomainReady(v2Config).get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private static void assertDomainState(DataSource dataSource, PersistenceDomain domain) throws SQLException {
        assertThat(readSchemaVersion(dataSource, domain.name())).isEqualTo(1);
        assertThat(tableExists(dataSource, domain.prefix() + "sample")).isTrue();
    }

    private static int readSchemaVersion(DataSource dataSource, String domainName) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_SCHEMA_VERSION_SQL)) {
            statement.setString(1, domainName);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getInt(1);
            }
        }
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

}
