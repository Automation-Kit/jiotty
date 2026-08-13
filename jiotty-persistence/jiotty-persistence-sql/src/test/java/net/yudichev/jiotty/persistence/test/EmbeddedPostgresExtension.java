package net.yudichev.jiotty.persistence.test;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// Boots one real Postgres for the registering class and drops its schema between tests. A class registering it is annotated [UsingEmbeddedPostgres], which
/// keeps two such classes from running at the same time.
///
/// Everything is served from **one pooled data source**, which bounds the open sockets by the pool size: zonky's [EmbeddedPostgres#getPostgresDatabase()]
/// opens a fresh TCP connection per [DataSource#getConnection()], and the stores under test take a connection per query, so unpooled they exhaust the
/// machine's ephemeral port range within one class and every later connect fails with `BindException: Can't assign requested address`.
public final class EmbeddedPostgresExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {
    private static final Logger logger = LogManager.getLogger(EmbeddedPostgresExtension.class);

    private static final String DROP_SCHEMA_SQL = "DROP SCHEMA IF EXISTS public CASCADE";
    private static final String CREATE_SCHEMA_SQL = "CREATE SCHEMA public";

    /// Generous enough that no test deadlocks waiting for a connection while holding another, small enough to keep the socket count irrelevant.
    private static final int MAX_POOL_SIZE = 16;

    private @Nullable EmbeddedPostgres postgres;
    private @Nullable HikariDataSource dataSource;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        Class<?> testClass = context.getRequiredTestClass();
        // Checked rather than inferred from two classes happening to overlap: the annotation is what serialises them, so its absence is the defect, whether or
        // not this particular run got away with it.
        checkState(testClass.isAnnotationPresent(UsingEmbeddedPostgres.class),
                   "%s registers an embedded Postgres, so it must be annotated @%s to keep two such classes from running at once",
                   testClass.getName(),
                   UsingEmbeddedPostgres.class.getSimpleName());
        EmbeddedPostgres startedPostgres = EmbeddedPostgres.start();
        postgres = startedPostgres;
        var pool = new HikariDataSource();
        pool.setDataSource(startedPostgres.getPostgresDatabase());
        pool.setMaximumPoolSize(MAX_POOL_SIZE);
        pool.setPoolName(testClass.getName());
        dataSource = pool;
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        HikariDataSource pool = pool();
        cleanSchema(pool);
        // The schema every pooled connection was talking to has just been replaced; retiring them keeps the next test off connections whose server-side
        // prepared statements still reference the dropped objects.
        pool.getHikariPoolMXBean().softEvictConnections();
    }

    @SuppressWarnings("AssignmentToNull")
    @Override
    public void afterAll(ExtensionContext context) {
        // The pool first: its connections outlive the server otherwise, and closing it afterwards would log a storm of failed evictions. Both are attempted
        // whatever either does, so a pool that fails to close cannot leave the postmaster running for the rest of the suite.
        closeSafelyIfNotNull(logger, dataSource, postgres);
        postgres = null;
        dataSource = null;
    }

    public DataSource dataSource() {
        return pool();
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

    private HikariDataSource pool() {
        checkState(dataSource != null, "Postgres data source is not initialised");
        return dataSource;
    }

    private static void cleanSchema(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(DROP_SCHEMA_SQL);
            statement.execute(CREATE_SCHEMA_SQL);
        }
    }
}
