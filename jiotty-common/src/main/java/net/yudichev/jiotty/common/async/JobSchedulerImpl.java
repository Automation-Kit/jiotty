package net.yudichev.jiotty.common.async;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Runnables;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;

public final class JobSchedulerImpl extends BaseLifecycleComponent implements JobScheduler {
    private static final Logger logger = LogManager.getLogger(JobSchedulerImpl.class);

    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final ZoneId zoneId;
    private final TaskFailureReporter taskFailureReporter;

    private SchedulingExecutor sharedScheduler;

    @Inject
    public JobSchedulerImpl(ExecutorFactory executorFactory,
                            CurrentDateTimeProvider currentDateTimeProvider,
                            @Dependency ZoneId zoneId,
                            TaskFailureReporter taskFailureReporter) {
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.zoneId = checkNotNull(zoneId);
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
    }

    @SuppressWarnings("ReturnOfInnerClass") // we are a singleton
    @Override
    public Closeable monthly(String jobName, int dayOfMonth, Runnable task) {
        return monthly(getSharedScheduler(), jobName, dayOfMonth, task);
    }

    @Override
    public Closeable monthly(Scheduler scheduler, String jobName, int dayOfMonth, Runnable task) {
        var job = new MonthlyJob(scheduler, jobName, dayOfMonth, task);
        job.scheduleNext();
        return job;
    }

    @Override
    public Closeable daily(String jobName, LocalTime time, Runnable task) {
        return daily(getSharedScheduler(), jobName, time, task);
    }

    @Override
    public Closeable daily(Scheduler scheduler, String jobName, LocalTime time, Runnable task) {
        return daily(scheduler, jobName, time, zoneId, task);
    }

    @Override
    public Closeable daily(Scheduler scheduler, String jobName, LocalTime time, ZoneId zoneId, Runnable task) {
        var job = new DailyJob(scheduler, jobName, time, zoneId, task);
        job.scheduleNext();
        return job;
    }

    private Scheduler getSharedScheduler() {
        return whenStartedAndNotLifecycling(() -> {
            if (sharedScheduler == null) {
                sharedScheduler = executorFactory.createSingleThreadedSchedulingExecutor("job-scheduler");
            }
            return sharedScheduler;
        });
    }

    @Override
    protected void doStop() {
        closeIfNotNull(sharedScheduler);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    abstract class ScheduledJob implements Closeable {
        private final Scheduler scheduler;
        private final String jobName;
        private final Runnable task;
        private final ZoneId jobZoneId;
        private final ZonedDateTime startTime;

        private int runNumber;
        private Closeable scheduleHandle;

        protected ScheduledJob(Scheduler scheduler, String jobName, ZoneId jobZoneId, Runnable task) {
            this.scheduler = checkNotNull(scheduler);
            this.jobName = checkNotNull(jobName);
            this.jobZoneId = checkNotNull(jobZoneId);
            // Guarded so a failing run reports its failure and goes on to schedule the next one.
            this.task = Runnables.guarded("executing job " + jobName, task, taskFailureReporter::onTaskException);
            startTime = currentDateTimeProvider.currentInstant().atZone(jobZoneId);
        }

        @Override
        public void close() {
            scheduleHandle.close();
        }

        public final void scheduleNext() {
            ZonedDateTime currentDateTime = currentDateTimeProvider.currentInstant().atZone(jobZoneId);
            ZonedDateTime nextDateTime;
            while ((nextDateTime = calculateNextTime(startTime, runNumber++)).isBefore(currentDateTime)) {
                if (runNumber > 1) {
                    logger.warn("[{}] Previous job overran, next scheduled time should have been {} but now is {}, will skip this run",
                                jobName, nextDateTime, currentDateTime);
                }
            }

            ZonedDateTime finalNextDateTime = nextDateTime;
            scheduleHandle = whenStartedAndNotLifecycling(() -> {
                Closeable handle = scheduler.schedule(Duration.between(currentDateTime, finalNextDateTime), this::trigger);
                logger.info("[{}] next job scheduled for {}", jobName, finalNextDateTime);
                return handle;
            });
        }

        protected abstract ZonedDateTime calculateNextTime(ZonedDateTime startTime, int runNumber);

        private void trigger() {
            logger.debug("[{}] executing", jobName);
            task.run();
            scheduleNext();
        }
    }

    private class MonthlyJob extends ScheduledJob {

        private static final LocalTime TIME_OF_RUN = LocalTime.of(3, 0, 0);
        private final int dayOfMonth;

        MonthlyJob(Scheduler scheduler, String jobName, int dayOfMonth, Runnable task) {
            super(scheduler, jobName, zoneId, task);
            this.dayOfMonth = dayOfMonth;
        }

        @Override
        protected ZonedDateTime calculateNextTime(ZonedDateTime startTime, int runNumber) {
            return startTime.plusMonths(runNumber).withDayOfMonth(dayOfMonth).with(TIME_OF_RUN);
        }
    }

    private class DailyJob extends ScheduledJob {
        private final LocalTime time;

        DailyJob(Scheduler scheduler, String jobName, LocalTime time, ZoneId jobZoneId, Runnable task) {
            super(scheduler, jobName, jobZoneId, task);
            this.time = checkNotNull(time);
        }

        @Override
        protected ZonedDateTime calculateNextTime(ZonedDateTime startTime, int runNumber) {
            return startTime.plusDays(runNumber).with(time);
        }
    }
}
