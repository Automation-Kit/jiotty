package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.LocalTime;
import java.time.ZoneId;

public interface JobScheduler {
    Closeable monthly(String jobName, int dayOfMonth, Runnable task);

    Closeable monthly(Scheduler scheduler, String jobName, int dayOfMonth, Runnable task);

    Closeable daily(String jobName, LocalTime time, Runnable task);

    Closeable daily(Scheduler scheduler, String jobName, LocalTime time, Runnable task);

    /// Schedules a daily job whose `time` is resolved in `zoneId`. Use it for a job anchored to an external party's
    /// clock — a market that publishes on UK time, say — so the job stays on that clock wherever the process happens to run.
    Closeable daily(Scheduler scheduler, String jobName, LocalTime time, ZoneId zoneId, Runnable task);
}
