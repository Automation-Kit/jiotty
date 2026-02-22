package net.yudichev.jiotty.persistence.db.psql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.inject.Inject;
import net.yudichev.jiotty.persistence.db.CloseableDataSource;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import net.yudichev.jiotty.persistence.db.JdbcConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

import static java.util.concurrent.TimeUnit.MINUTES;

public final class PsqlDataSourceFactoryImpl implements DataSourceFactory {
    private static final Logger logger = LoggerFactory.getLogger(PsqlDataSourceFactoryImpl.class);

    private final HikariConfig poolConfig;

    @Inject
    public PsqlDataSourceFactoryImpl(JdbcConnectionConfig connectionConfig) {
        poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(connectionConfig.url());
        poolConfig.setUsername(connectionConfig.username());
        poolConfig.setPassword(connectionConfig.password());
        poolConfig.setMaximumPoolSize(2);
        poolConfig.setMinimumIdle(2);
        // added after this error was logged
        // Failed to validate connection org.postgresql.jdbc.PgConnection@3cadc946 (This connection has been closed.).
        // Possibly consider using a shorter maxLifetime value.
        poolConfig.setMaxLifetime(MINUTES.toMillis(20));
    }

    @Override
    public CloseableDataSource create() {
        logger.info("Initialising pool for {}", poolConfig.getJdbcUrl());
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
