package net.yudichev.jiotty.adminalerts;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.persistence.domain.PersistenceDomain;
import net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig;

import java.util.List;

import static net.yudichev.jiotty.persistence.domain.PersistenceDomainConfig.DOMAIN_PREFIX_PLACEHOLDER;

/// SQL schema of the [PersistenceDomain] that holds the [AdminAlertService] alerts, named {@value #DEFAULT_DOMAIN_NAME} by default. In every statement,
/// each table, type and index name starts with [PersistenceDomainConfig#DOMAIN_PREFIX_PLACEHOLDER].
final class AdminAlertSchema {
    public static final String DEFAULT_DOMAIN_NAME = "admin_alerts";
    static final String GRAFANA_READER_ROLE = "grafana_reader";

    // buildInitStatements returns an ImmutableList, which no caller can change.
    @SuppressWarnings("PublicStaticCollectionField")
    public static final List<String> INIT_STATEMENTS = buildInitStatements();

    private AdminAlertSchema() {
    }

    private static List<String> buildInitStatements() {
        // Severity ENUM. CREATE TYPE has no IF NOT EXISTS in PostgreSQL; gate via pg_type lookup so re-running is safe.
        String createSeverityType = """
                                    DO $do$
                                    BEGIN
                                        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = '%DOMAIN_PREFIX%severity') THEN
                                            CREATE TYPE %DOMAIN_PREFIX%severity AS ENUM ('WARNING', 'ERROR', 'CRITICAL');
                                        END IF;
                                    END
                                    $do$;""";

        String createAlertTable = """
                                  CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%alert (
                                      id              text PRIMARY KEY,
                                      dedup_key       text NOT NULL,
                                      title           text NOT NULL,
                                      severity        %DOMAIN_PREFIX%severity NOT NULL,
                                      labels          jsonb NOT NULL DEFAULT '{}'::jsonb,
                                      first_seen_at   timestamptz NOT NULL,
                                      last_seen_at    timestamptz NOT NULL,
                                      event_count     integer NOT NULL DEFAULT 0,
                                      resolved_at     timestamptz,
                                      resolved_by     text,
                                      resolution_note text
                                  );""";

        String createEventTable = """
                                  CREATE TABLE IF NOT EXISTS %DOMAIN_PREFIX%alert_event (
                                      id          bigserial PRIMARY KEY,
                                      alert_id    text NOT NULL REFERENCES %DOMAIN_PREFIX%alert (id) ON DELETE CASCADE,
                                      occurred_at timestamptz NOT NULL,
                                      description text NOT NULL
                                  );""";

        // Grafana read-only role. Created NOLOGIN; an operator runs ALTER ROLE ... LOGIN PASSWORD '<random>' once after first deploy and stores the password
        // only in Grafana Cloud's datasource secret.
        String createGrafanaReaderRole = ("""
                                          DO $do$
                                          BEGIN
                                              IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%ROLE%') THEN
                                                  CREATE ROLE %ROLE% NOLOGIN;
                                              END IF;
                                          END
                                          $do$;""").replace("%ROLE%", GRAFANA_READER_ROLE);

        return ImmutableList.of(
                createSeverityType,
                createAlertTable,
                createEventTable,
                "CREATE UNIQUE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "alert_active_dedup_uidx " +
                "ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert (dedup_key) WHERE resolved_at IS NULL;",
                "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "alert_active_idx " +
                "ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert (last_seen_at DESC) WHERE resolved_at IS NULL;",
                "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "alert_resolved_at_idx " +
                "ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert (resolved_at DESC) WHERE resolved_at IS NOT NULL;",
                "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "alert_labels_gin " +
                "ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert USING gin (labels jsonb_path_ops);",
                "CREATE INDEX IF NOT EXISTS " + DOMAIN_PREFIX_PLACEHOLDER + "alert_event_alert_id_idx " +
                "ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert_event (alert_id, occurred_at DESC);",
                createGrafanaReaderRole,
                // CONNECT to the database is granted to PUBLIC by default; a fresh role inherits that and needs no explicit grant. USAGE on the public schema
                // is also granted to PUBLIC by default in Postgres < 15; in 15+ it must be granted explicitly per role.
                "GRANT USAGE ON SCHEMA public TO " + GRAFANA_READER_ROLE + ';',
                "GRANT SELECT ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert TO " + GRAFANA_READER_ROLE + ';',
                "GRANT SELECT ON " + DOMAIN_PREFIX_PLACEHOLDER + "alert_event TO " + GRAFANA_READER_ROLE + ';');
    }
}
