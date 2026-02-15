package net.yudichev.jiotty.persistence.psql;

public interface PsqlDataSourceFactory {
    CloseableDataSource create();
}
