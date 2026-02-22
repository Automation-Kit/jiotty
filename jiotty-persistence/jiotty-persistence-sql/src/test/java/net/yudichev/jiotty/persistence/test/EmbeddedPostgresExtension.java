package net.yudichev.jiotty.persistence.test;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static com.google.common.base.Preconditions.checkState;

public final class EmbeddedPostgresExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {
    private static final String DROP_SCHEMA_SQL = "DROP SCHEMA IF EXISTS public CASCADE";
    private static final String CREATE_SCHEMA_SQL = "CREATE SCHEMA public";
    private EmbeddedPostgres postgres;
    private DataSource dataSource;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        cleanSchema(dataSource());
    }

    @SuppressWarnings("AssignmentToNull")
    @Override
    public void afterAll(ExtensionContext context) {
        Closeable.closeIfNotNull(postgres);
        postgres = null;
        dataSource = null;
    }

    public DataSource dataSource() {
        checkState(dataSource != null, "Postgres data source is not initialised");
        return dataSource;
    }

    public DataSourceFactory dataSourceFactory() {
        return () -> new CloseableDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return dataSource().getConnection();
            }

            @Override
            public void close() {
            }
        };
    }

    private static void cleanSchema(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(DROP_SCHEMA_SQL);
            statement.execute(CREATE_SCHEMA_SQL);
        }
    }
}
