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
import java.util.concurrent.Executor;

public interface Reader {
    CompletableFuture<Void> query(String queryTemplate,
                                  QueryStmtParamValueSetter paramValueSetter,
                                  ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler);

    /// As [#query(String, QueryStmtParamValueSetter, ThrowingConsumer)], but runs the query — and invokes `rowHandler` for each row — on `queryExecutor`
    /// rather than the reader's own executor. For callers that must confine row handling to a thread of their choosing, e.g. streaming a large result straight
    /// to a client without occupying (or blocking) a shared recording executor. `queryExecutor` must be effectively single-threaded for the call.
    ///
    /// @implSpec The default runs on the reader's own executor, ignoring `queryExecutor`; an implementation backed by a shared executor overrides this to
    ///  run on the supplied executor.
    default CompletableFuture<Void> query(Executor queryExecutor,
                                          String queryTemplate,
                                          QueryStmtParamValueSetter paramValueSetter,
                                          ThrowingConsumer<? super QueryResultRow, ? extends SQLException> rowHandler) {
        return query(queryTemplate, paramValueSetter, rowHandler);
    }

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
