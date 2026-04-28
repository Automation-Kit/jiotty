package net.yudichev.jiotty.adminalerts.cleanup;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Periodic deletion of resolved alerts older than the configured retention.
///
/// State (next run timestamp) is persisted in [VarStore] so missed windows are caught up automatically on restart: a past-due `next_at` means the next
/// scheduled task fires with a delay of zero.
public final class AlertHistoryCleanupJob extends BaseLifecycleComponent {
    static final String NEXT_AT_KEY = "admin_alerts.cleanup.next_at";
    private static final Logger logger = LogManager.getLogger(AlertHistoryCleanupJob.class);
    private final AdminAlertService alertService;
    private final VarStore varStore;
    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider timeProvider;
    private final Duration interval;
    private final Duration retention;
    private @Nullable Closeable scheduledTask;

    private SchedulingExecutor executor;

    @Inject
    public AlertHistoryCleanupJob(AdminAlertService alertService,
                                  @Dependency VarStore varStore,
                                  ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider timeProvider,
                                  @CleanupInterval Duration interval,
                                  @HistoryRetention Duration retention) {
        this.alertService = checkNotNull(alertService, "alertService");
        this.varStore = checkNotNull(varStore, "varStore");
        this.executorFactory = checkNotNull(executorFactory);
        this.timeProvider = checkNotNull(timeProvider, "timeProvider");
        this.interval = checkNotNull(interval, "interval");
        checkArgument(interval.isPositive(), "interval must be positive, was %s", interval);
        this.retention = checkNotNull(retention, "retention");
        checkArgument(retention.isPositive(), "retention must be positive, was %s", retention);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(AlertHistoryCleanupJob.class.getSimpleName());
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
        logger.info("Scheduling next admin-alert history cleanup at {} (in {})", nextAt, delay);
        scheduleNext(delay);
    }

    private void scheduleNext(Duration delay) {
        Closeable.closeIfNotNull(scheduledTask);
        scheduledTask = executor.schedule(delay, this::runCleanup);
    }

    private void runCleanup() {
        alertService.deleteResolvedOlderThan(retention)
                    .whenComplete((rows, error) -> {
                        if (error != null) {
                            logger.info("Admin-alert history cleanup failed; will retry next interval", error);
                        } else {
                            Instant nextAt = timeProvider.currentInstant().plus(interval);
                            varStore.saveValue(NEXT_AT_KEY, nextAt);
                            logger.info("Admin-alert history cleanup deleted {} resolved alerts; next run scheduled at {}", rows, nextAt);
                        }
                        scheduleNext(interval);
                    });
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
    public @interface HistoryRetention {
    }
}
