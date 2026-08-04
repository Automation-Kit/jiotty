package net.yudichev.jiotty.persistence.db.psql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.JdbcConnectionConfig;
import net.yudichev.jiotty.persistence.db.psql.PsqlDataSourceFactoryModule.MaximumPoolSize;
import net.yudichev.jiotty.persistence.db.psql.PsqlDataSourceFactoryModule.PoolMeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.sql.Connection;
import java.sql.SQLException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MINUTES;

public final class PsqlDataSourceFactoryImpl implements DataSourceFactory {
    private static final Logger logger = LogManager.getLogger(PsqlDataSourceFactoryImpl.class);

    private final JdbcConnectionConfig connectionConfig;
    private final int maximumPoolSize;
    private final MeterRegistry meterRegistry;

    @Inject
    public PsqlDataSourceFactoryImpl(JdbcConnectionConfig connectionConfig,
                                     @MaximumPoolSize int maximumPoolSize,
                                     @PoolMeterRegistry MeterRegistry meterRegistry) {
        checkArgument(maximumPoolSize > 0, "maximum pool size must be positive: %s", maximumPoolSize);
        this.connectionConfig = checkNotNull(connectionConfig, "connectionConfig");
        this.maximumPoolSize = maximumPoolSize;
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
    }

    @Override
    public CloseableDataSource create() {
        return build(null);
    }

    @Override
    public CloseableDataSource create(String poolName) {
        return build(checkNotNull(poolName, "poolName"));
    }

    /// Builds a fresh pool per call — a HikariConfig is single-use (sealed once a data source is built from it), so it cannot be shared across pools. A
    /// non-null `poolName` becomes the `pool` metric tag; a null one leaves HikariCP to auto-name.
    private CloseableDataSource build(@Nullable String poolName) {
        var poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(connectionConfig.url());
        poolConfig.setUsername(connectionConfig.username());
        poolConfig.setPassword(connectionConfig.password());
        // Fixed-size pool: minimum-idle pinned to the maximum, as HikariCP recommends, so the connections stay open and a request never waits on
        // connection establishment under load.
        poolConfig.setMaximumPoolSize(maximumPoolSize);
        poolConfig.setMinimumIdle(maximumPoolSize);
        // added after this error was logged
        // Failed to validate connection org.postgresql.jdbc.PgConnection@3cadc946 (This connection has been closed.).
        // Possibly consider using a shorter maxLifetime value.
        poolConfig.setMaxLifetime(MINUTES.toMillis(20));
        if (poolName != null) {
            poolConfig.setPoolName(poolName);
        }
        // Surface pool gauges (hikaricp_connections_{active,idle,max,pending,…}) tagged by pool name. The module defaults the registry to a NoopMeterRegistry,
        // so this is a no-op for callers that do not wire real metrics.
        poolConfig.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        logger.info("Initialising pool {} for {}", poolName == null ? "(unnamed)" : poolName, poolConfig.getJdbcUrl());
        //noinspection IOResourceOpenedButNotSafelyClosed
        @SuppressWarnings("resource")
        var hikariDataSource = new HikariDataSource(poolConfig);
        return new CloseableDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return hikariDataSource.getConnection();
            }

            @Override
            public void close() {
                hikariDataSource.close();
            }
        };
    }
}
