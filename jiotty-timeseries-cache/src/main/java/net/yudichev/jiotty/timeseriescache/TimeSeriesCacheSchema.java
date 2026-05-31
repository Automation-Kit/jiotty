package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainService;

import java.util.List;

/// SQL schema for the `time_series_cache` persistence domain. The `%DOMAIN_PREFIX%` placeholder is filled in at execution time by [PersistenceDomainService].
final class TimeSeriesCacheSchema {
    static final String DEFAULT_DOMAIN_NAME = "time_series_cache";

    static final List<String> INIT_STATEMENTS = ImmutableList.of(
            """
            CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%entry (
                scope_kind   smallint    NOT NULL,
                scope_value  text,
                stream_id    text        NOT NULL,
                slot_start   timestamptz NOT NULL,
                value        bytea       NOT NULL,
                created_at   timestamptz NOT NULL,
                updated_at   timestamptz NOT NULL
            );""",
            // Postgres rejects nullable columns in a PRIMARY KEY, so the row uniqueness lives in a UNIQUE INDEX with NULLS NOT DISTINCT (PG 15+): two
            // rows whose scope_value is both NULL collide as expected, matching the User/Region case where two rows with the same non-null value collide.
            "CREATE UNIQUE INDEX IF NOT EXISTS %DOMAIN_PREFIX%entry_uk " +
            "ON %DOMAIN_PREFIX%entry (scope_kind, scope_value, stream_id, slot_start) NULLS NOT DISTINCT;",
            // Secondary index supports cleanup-by-stream (deleteAllForStream) without scanning the full UK btree.
            "CREATE INDEX IF NOT EXISTS %DOMAIN_PREFIX%entry_stream_id_idx ON %DOMAIN_PREFIX%entry (stream_id);",
            // Supports the retention purge (deleteOlderThan: WHERE slot_start < ?) so the daily cleanup is an index range scan, not a full-table scan.
            "CREATE INDEX IF NOT EXISTS %DOMAIN_PREFIX%entry_slot_start_idx ON %DOMAIN_PREFIX%entry (slot_start);");

    private TimeSeriesCacheSchema() {
    }
}
