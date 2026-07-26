package net.yudichev.jiotty.persistence.db;

public interface DataSourceFactory {
    /// Creates a new connection pool with a HikariCP-assigned name. Prefer [#create(String)] where the pool's metrics should carry a stable, meaningful `pool`
    /// label.
    CloseableDataSource create();

    /// Creates a new connection pool named `poolName`, which becomes the `pool` tag on its HikariCP metrics. The default delegates to [#create()] (ignoring the
    /// name) for implementations that do not surface pool metrics; the SQL implementation honours it.
    default CloseableDataSource create(String poolName) {
        return create();
    }
}
