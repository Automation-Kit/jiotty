package net.yudichev.jiotty.persistence.recording;

import net.yudichev.jiotty.common.lang.ThrowingConsumer;
import net.yudichev.jiotty.common.lang.ThrowingSupplier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.concurrent.CompletableFuture;

public interface Reader {
    CompletableFuture<Void> query(String queryTemplate,
                                  QueryStmtParamValueSetter paramValueSetter,
                                  ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler);

    interface QueryStmtParamValueSetter {
        void set(Input input) throws SQLException;

        record Input(Calendar cal, Connection conn, PreparedStatement stmt) {
            public Input setTimestamp(int colIdx, Instant value) throws SQLException {
                stmt().setTimestamp(colIdx, Timestamp.from(value), cal);
                return this;
            }
        }
    }

    record QueryResultRow(Calendar cal, Connection conn, ResultSet rs, ThrowingSupplier<Instant, SQLException> timestampReader) {}
}
