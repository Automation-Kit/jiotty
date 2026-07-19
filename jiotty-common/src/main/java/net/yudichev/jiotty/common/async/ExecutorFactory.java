package net.yudichev.jiotty.common.async;

import java.util.concurrent.RejectedExecutionException;

public interface ExecutorFactory {
    /// Default bound on an executor's pending immediate-task (`execute`/`submit`, not scheduled) queue; a `submit`/`execute` beyond it is rejected with
    /// [RejectedExecutionException].
    int DEFAULT_MAX_QUEUE_SIZE = 5_000;

    /// Creates a single-threaded scheduling executor named `name` (its thread-name base, per-instance metric `name` tag, and — absent an explicit one — metric
    /// `family` tag), bounded at [#DEFAULT_MAX_QUEUE_SIZE].
    default SchedulingExecutor createSingleThreadedSchedulingExecutor(String name) {
        return createSingleThreadedSchedulingExecutor(name, name, DEFAULT_MAX_QUEUE_SIZE);
    }

    /// As [#createSingleThreadedSchedulingExecutor(String)] with an explicit queue bound; the metric `family` tag defaults to `name`.
    default SchedulingExecutor createSingleThreadedSchedulingExecutor(String name, int maxQueueSize) {
        return createSingleThreadedSchedulingExecutor(name, name, maxQueueSize);
    }

    /// Creates a single-threaded scheduling executor. `name` is the thread-name base and the per-instance metric `name` tag; `family` is the coarse metric tag
    /// used to aggregate across instances (`sum by (family)`); `maxQueueSize` bounds pending immediate (`execute`/`submit`, not scheduled) tasks — a submit
    /// beyond it is rejected with [RejectedExecutionException] (scheduled/periodic tasks do not count against the bound).
    SchedulingExecutor createSingleThreadedSchedulingExecutor(String name, String family, int maxQueueSize);
}
