package net.yudichev.jiotty.persistence.test;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static com.google.common.base.Preconditions.checkState;

public final class EmbeddedPostgresExtension implements BeforeEachCallback, AfterEachCallback {
    private EmbeddedPostgres postgres;
    private DataSource dataSource;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        postgres = EmbeddedPostgres.start();
        dataSource = postgres.getPostgresDatabase();
    }

    @SuppressWarnings("AssignmentToNull")
    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (postgres != null) {
            postgres.close();
            postgres = null;
            dataSource = null;
        }
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
}
