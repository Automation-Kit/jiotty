package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.concurrent.Callable;

/// An executor that both runs tasks immediately and schedules them for later, and that is closed when its owner shuts down.
///
/// Closing drains the immediate-task backlog ([TaskExecutor#execute(Runnable)] / [TaskExecutor#submit(Callable)]) to completion before shutting down, but
/// discards any work scheduled via [Scheduler#schedule] / [Scheduler#scheduleAtFixedRate] that has not yet fired.
public interface SchedulingExecutor extends TaskExecutor, Closeable, Scheduler {
}
