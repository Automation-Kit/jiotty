package net.yudichev.jiotty.timeseriescache.cleanup;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Periodic time-series cache cleanup. On each tick the job purges rows older than the retention horizon (the storage-limitation control that bounds cache
/// growth), then — if an [ActiveUserIdsSupplier] was wired — evicts rows belonging to deleted user accounts. State (next run timestamp) is persisted in
/// [VarStore] so missed windows are caught up automatically on restart.
public final class TimeSeriesCacheCleanupJob extends BaseLifecycleComponent {
    static final String NEXT_AT_KEY = "time_series_cache.cleanup.next_at";
    private static final Logger logger = LogManager.getLogger(TimeSeriesCacheCleanupJob.class);

    private final TimeSeriesCache cache;
    private final Optional<ActiveUserIdsSupplier> activeUserIdsSupplier;
    private final VarStore varStore;
    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider timeProvider;
    private final Duration interval;
    private final Duration retention;

    private @Nullable Closeable scheduledTask;
    private SchedulingExecutor executor;

    @Inject
    public TimeSeriesCacheCleanupJob(TimeSeriesCache cache,
                                     Optional<ActiveUserIdsSupplier> activeUserIdsSupplier,
                                     @Dependency VarStore varStore,
                                     ExecutorFactory executorFactory,
                                     CurrentDateTimeProvider timeProvider,
                                     @CleanupInterval Duration interval,
                                     @CleanupRetention Duration retention) {
        this.cache = checkNotNull(cache, "cache");
        // Optional: the retention purge below needs no user set. When present, the orphan-eviction path (still a framework stub) additionally runs.
        this.activeUserIdsSupplier = checkNotNull(activeUserIdsSupplier, "activeUserIdsSupplier");
        this.varStore = checkNotNull(varStore, "varStore");
        this.executorFactory = checkNotNull(executorFactory, "executorFactory");
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        this.interval = checkNotNull(interval, "interval");
        checkArgument(interval.isPositive(), "interval must be positive, was %s", interval);
        this.retention = checkNotNull(retention, "retention");
        checkArgument(retention.isPositive(), "retention must be positive, was %s", retention);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(TimeSeriesCacheCleanupJob.class.getSimpleName());
        executor.execute(this::scheduleFromPersistedState);
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, scheduledTask, executor);
    }

    private void scheduleFromPersistedState() {
        Instant now = timeProvider.currentInstant();
        Instant nextAt = readNextAt().orElse(now);
        Duration delay = Duration.between(now, nextAt);
        if (!delay.isPositive()) {
            delay = Duration.ZERO;
        }
        logger.info("Scheduling next time-series cache cleanup at {} (in {})", nextAt, delay);
        scheduleNext(delay);
    }

    private void scheduleNext(Duration delay) {
        Closeable.closeIfNotNull(scheduledTask);
        scheduledTask = executor.schedule(delay, this::runCleanup);
    }

    private void runCleanup() {
        cache.deleteOlderThan(timeProvider.currentInstant().minus(retention))
             .thenComposeAsync(purged -> {
                 logger.info("Time-series cache retention purge deleted {} row(s) older than {}", purged, retention);
                 return activeUserIdsSupplier
                         .map(supplier -> supplier.get().thenComposeAsync(TimeSeriesCacheCleanupJob::evictOrphans, executor))
                         .orElseGet(() -> CompletableFuture.completedFuture(0));
             }, executor)
             .whenCompleteAsync((_, error) -> {
                 if (error != null) {
                     logger.info("Time-series cache cleanup failed; will retry next interval", error);
                 } else {
                     Instant nextAt = timeProvider.currentInstant().plus(interval);
                     varStore.saveValue(NEXT_AT_KEY, nextAt);
                     logger.info("Time-series cache cleanup complete; next run scheduled at {}", nextAt);
                 }
                 scheduleNext(interval);
             }, executor);
    }

    private static CompletableFuture<Integer> evictOrphans(Set<String> activeUserIds) {
        // We don't currently have a way to enumerate all userIds that have rows in the cache without a scan.
        // For the first iteration, we accept that orphan eviction is driven explicitly by deletion events
        // (TimeSeriesCache.deleteAllForScope) called from user-deletion code paths. This periodic job
        // exists as the framework hook for a future enumeration-based sweep.
        logger.debug("Time-series cache cleanup tick: {} active user id(s); no enumeration sweep implemented yet", activeUserIds.size());
        return CompletableFuture.completedFuture(0);
    }

    private Optional<Instant> readNextAt() {
        return varStore.readValue(TypeToken.of(Instant.class), NEXT_AT_KEY);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface CleanupInterval {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface CleanupRetention {
    }
}
