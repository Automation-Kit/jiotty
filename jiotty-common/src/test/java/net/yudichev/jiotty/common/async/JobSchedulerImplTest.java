package net.yudichev.jiotty.common.async;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JobSchedulerImplTest {

    @Mock
    private TaskFailureReporter taskFailureReporter;
    private ProgrammableClock clock;
    private JobSchedulerImpl scheduler;
    private int execCount;
    private Runnable task;
    private Duration taskRunTime = Duration.ZERO;
    private ZoneId zoneId = ZoneOffset.UTC;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc().withTasksSeeingTargetTime(true);
        task = () -> {
            clock.advanceTime(taskRunTime);
            execCount++;
        };
    }

    @Test
    void daily() {
        startScheduler();

        var jobHandle = scheduler.daily("dailyJob", LocalTime.of(9, 0), task);
        clock.tick();
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.EPOCH.plus(8, HOURS));
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.EPOCH.plus(9, HOURS));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.EPOCH.plus(1, DAYS).plus(8, HOURS));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.EPOCH.plus(1, DAYS).plus(9, HOURS));
        assertThat(execCount).isEqualTo(2);

        jobHandle.close();
        clock.setTimeAndTick(Instant.EPOCH.plus(10, DAYS));
        assertThat(execCount).isEqualTo(2);
    }

    @Test
    void daily_taskOverruns() {
        startScheduler();

        scheduler.daily("dailyJob", LocalTime.of(9, 0), task);
        // scheduled: 1 jan 9:00 << effectively overruns by 3 days 5 hours
        // scheduled: 2 jan 9:00 << skipped
        // scheduled: 3 jan 9:00 << skipped
        // scheduled: 4 jan 9:00 << executed
        // ...

        taskRunTime = Duration.ofDays(2).plusHours(5);
        clock.setTimeAndTick(Instant.EPOCH.plus(1, DAYS));
        // ticked to 2 jan 00:00, executed 1 task which advances to 4 jan 5:00
        assertThat(execCount).isEqualTo(1);

        // next task will overrun
        taskRunTime = Duration.ZERO;
        // now next task should execute on 4 jan 9:00
        clock.tick();
        assertThat(execCount).isEqualTo(1);
        clock.setTimeAndTick(Instant.EPOCH.plus(3, DAYS).plus(9, HOURS));
        assertThat(execCount).isEqualTo(2);
    }

    @Test
    void monthly() {
        startScheduler();

        var jobHandle = scheduler.monthly("monthlyJob", 2, task);
        clock.tick();
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.parse("1970-01-02T00:02:00Z"));
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.parse("1970-01-02T03:00:00Z"));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.parse("1970-02-02T02:00:00Z"));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.parse("1970-02-02T03:00:00Z"));
        assertThat(execCount).isEqualTo(2);

        jobHandle.close();
        clock.setTimeAndTick(Instant.parse("1970-05-01T00:00:00Z"));
        assertThat(execCount).isEqualTo(2);
    }

    @Test
    void daily_acrossDst() {
        zoneId = ZoneId.of("Europe/London");
        startScheduler();

        // schedule job to 16:00 before BST
        clock.setTime(Instant.parse("2025-10-25T11:00:00Z"));

        scheduler.daily("dailyJob", LocalTime.of(16, 0), task);
        clock.tick();
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.parse("2025-10-25T15:59:59+01:00"));
        assertThat(execCount).isEqualTo(0);

        clock.setTimeAndTick(Instant.parse("2025-10-25T16:00:00+01:00"));
        assertThat(execCount).isEqualTo(1);

        // across DST change from BST to GMT
        clock.setTimeAndTick(Instant.parse("2025-10-26T11:00:00Z"));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.parse("2025-10-26T15:59:59Z"));
        assertThat(execCount).isEqualTo(1);

        clock.setTimeAndTick(Instant.parse("2025-10-26T16:00:00Z"));
        assertThat(execCount).isEqualTo(2);
    }

    /// A job that throws reports the failure and goes on to schedule its next run.
    @Test
    void failingJobIsReportedAndKeepsRunning() {
        startScheduler();
        var failure = new RuntimeException("boom");
        task = () -> {
            execCount++;
            throw failure;
        };

        scheduler.daily("dailyJob", LocalTime.of(9, 0), task);

        clock.setTimeAndTick(Instant.EPOCH.plus(9, HOURS));
        assertThat(execCount).isEqualTo(1);
        verify(taskFailureReporter).onTaskException("executing job dailyJob", failure);

        clock.setTimeAndTick(Instant.EPOCH.plus(1, DAYS).plus(9, HOURS));
        assertThat(execCount).isEqualTo(2);
    }

    private void startScheduler() {
        scheduler = new JobSchedulerImpl(clock, clock, zoneId, taskFailureReporter);
        scheduler.start();
    }
}
