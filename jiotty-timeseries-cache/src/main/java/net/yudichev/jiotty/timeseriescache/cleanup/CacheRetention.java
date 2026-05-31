package net.yudichev.jiotty.timeseriescache.cleanup;

import java.time.Duration;

/// Single source of truth for the cache's retention horizon — the age beyond which stored slots are purged by [TimeSeriesCacheCleanupJob] and below which no
/// read is served. Kept as one constant so the purge horizon and any request-side floor that consumes it cannot drift. Domain-neutral: the substrate stores
/// arbitrary time-series, so the value is a property of the cache's storage management, not of any one consumer. A later refactor can promote this to a real
/// config knob (e.g. a bound `Duration`); for now it is a plain constant referenced directly by the cleanup job's default and by request-side floors.
public final class CacheRetention {
    /// Slots older than this are purged and never read. Five years.
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(365L * 5);

    private CacheRetention() {
    }
}
