package net.yudichev.jiotty.persistence.psql;

import net.yudichev.jiotty.common.lang.Closeable;

import java.sql.Connection;
import java.sql.SQLException;

public interface CloseableDataSource extends Closeable {
    Connection getConnection() throws SQLException;
}
