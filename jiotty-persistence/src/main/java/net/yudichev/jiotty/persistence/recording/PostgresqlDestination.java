package net.yudichev.jiotty.persistence.recording;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.ThrowingFunction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

public interface PostgresqlDestination extends Destination {
    void initialise();

    interface InsertStmtColValueSetter<R> {
        void set(Input<R> input) throws SQLException;

        record Input<R>(R record, Calendar cal, Connection conn, PreparedStatement stmt, int colIdx) {}
    }

    interface QueryStmtColValueGetter<T> {
        T get(Reader.QueryResultRow row, int colIdx) throws SQLException;
    }

    interface Migrator {
        Migrator NO_OP = (_) -> Set.of();

        /// @param toVersion migrate from (`toVersion` - 1) to (`toVersion`)
        Set<String> getMigrationStatements(int toVersion);
    }

    record Column<R, T>(String name,
                        String sqlType,
                        boolean nullable,
                        String valuePlaceholder,
                        InsertStmtColValueSetter<R> stmtColValueSetter,
                        @Nullable QueryStmtColValueGetter<T> queryStmtColValueGetter) {
        @SuppressWarnings("BooleanParameter")
        public Column(String name,
                      String sqlType,
                      boolean nullable,
                      String valuePlaceholder,
                      InsertStmtColValueSetter<R> stmtColValueSetter) {
            this(name, sqlType, nullable, valuePlaceholder, stmtColValueSetter, null);
        }

        public T get(Reader.QueryResultRow row, int colIdx) throws SQLException {
            return requireQueryStmtColvalueGetter().get(row, colIdx);
        }

        public QueryStmtColValueGetter<T> requireQueryStmtColvalueGetter() {
            return checkNotNull(queryStmtColValueGetter(), "Column %s does not have query value getter", name);
        }
    }

    record PsqlConfig<R>(Class<R> recordType, String typeId, int schemaVersion, Set<String> initStatements, Migrator migrator, List<Column<R, ?>> columns)
            implements Destination.Config<R> {
        @Override
        public DestinationType destinationType() {
            return DestinationType.POSTGRESQL;
        }

        public static <T, U> @Nullable U transform(@Nullable T value,
                                                   ThrowingFunction<? super T, ? extends U, ? extends SQLException> transform) throws SQLException {
            return value == null ? null : transform.apply(value);
        }
    }
}
