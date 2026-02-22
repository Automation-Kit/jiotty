package net.yudichev.jiotty.persistence.db;

public interface DataSourceFactory {
    CloseableDataSource create();
}
