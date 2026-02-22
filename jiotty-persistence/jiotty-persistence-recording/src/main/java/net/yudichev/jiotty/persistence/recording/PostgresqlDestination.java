package net.yudichev.jiotty.persistence.recording;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.ThrowingFunction;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainMigrator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public interface PostgresqlDestination extends Destination {
    void initialise();

    interface InsertStmtColValueSetter<R> {
        void set(Input<R> input) throws SQLException;

        record Input<R>(R record, Calendar cal, Connection conn, PreparedStatement stmt, int colIdx) {
            public Input {
                checkNotNull(record, "record");
                checkNotNull(cal, "cal");
                checkNotNull(conn, "conn");
                checkNotNull(stmt, "stmt");
                checkArgument(colIdx > 0, "colIdx must be > 0, was %s", colIdx);
            }
        }
    }

    interface QueryStmtColValueGetter<T> {
        @Nullable
        T get(Reader.QueryResultRow row, int colIdx) throws SQLException;
    }

    record Column<R, T>(String name,
                        String sqlType,
                        boolean nullable,
                        String valuePlaceholder,
                        InsertStmtColValueSetter<R> stmtColValueSetter,
                        @Nullable QueryStmtColValueGetter<T> queryStmtColValueGetter) {
        public Column {
            checkNotNull(name, "name");
            checkArgument(!name.isBlank(), "name must not be blank");
            checkNotNull(sqlType, "sqlType");
            checkArgument(!sqlType.isBlank(), "sqlType must not be blank");
            checkNotNull(valuePlaceholder, "valuePlaceholder");
            checkArgument(!valuePlaceholder.isBlank(), "valuePlaceholder must not be blank");
            checkNotNull(stmtColValueSetter, "stmtColValueSetter");
        }

        @SuppressWarnings("BooleanParameter")
        public Column(String name,
                      String sqlType,
                      boolean nullable,
                      String valuePlaceholder,
                      InsertStmtColValueSetter<R> stmtColValueSetter) {
            this(name, sqlType, nullable, valuePlaceholder, stmtColValueSetter, null);
        }

        public @Nullable T get(Reader.QueryResultRow row, int colIdx) throws SQLException {
            return requireQueryStmtColvalueGetter().get(row, colIdx);
        }

        public QueryStmtColValueGetter<T> requireQueryStmtColvalueGetter() {
            return checkNotNull(queryStmtColValueGetter(), "Column %s does not have query value getter", name);
        }
    }

    record PsqlConfig<R>(Class<R> recordType, String typeId, PersistenceDomainConfig domainConfig, List<Column<R, ?>> columns)
            implements Destination.Config<R> {
        public PsqlConfig {
            checkNotNull(recordType, "recordType");
            checkNotNull(typeId, "typeId");
            checkArgument(!typeId.isBlank(), "typeId must not be blank");
            checkNotNull(domainConfig, "domainConfig");
            columns = List.copyOf(checkNotNull(columns, "columns"));
        }

        public PsqlConfig(Class<R> recordType,
                          String typeId,
                          int schemaVersion,
                          List<String> initStatements,
                          PersistenceDomainMigrator migrator,
                          List<Column<R, ?>> columns) {
            this(recordType,
                 typeId,
                 new PersistenceDomainConfig(new PersistenceDomain(typeId),
                                             schemaVersion,
                                             checkNotNull(initStatements, "initStatements"),
                                             checkNotNull(migrator, "migrator")),
                 columns);
        }

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
