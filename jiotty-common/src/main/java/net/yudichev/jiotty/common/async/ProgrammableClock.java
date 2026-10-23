package net.yudichev.jiotty.common.async;

import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.ThreadContext;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.SECONDS;

public final class ProgrammableClock implements CurrentDateTimeProvider, ExecutorFactory {
    /// The most tasks a chain may run at one instant, each scheduled by the one before it, before the next task in the chain fails. A live executor would keep
    /// its thread busy on such a chain, and since time stands still while a task runs, [#tick()] would never return.
    @VisibleForTesting
    static final int MAX_TASK_CHAIN_AT_ONE_INSTANT = 10_000;
    @VisibleForTesting
    static final String MDC_THREAD_KEY = "thread";
    @VisibleForTesting
    static final String MDC_TASK_DUE_KEY = "task.due";
    private static final String MDC_CURRENT_TIME_KEY = "current.time";

    private final NavigableMap<Instant, List<Task>> tasksByTriggerTime = new TreeMap<>();
    private Instant currentTime = Instant.EPOCH;
    private boolean mdc;
    private @Nullable Instant currentTaskTime;
    private @Nullable Task currentTask;
    private ZoneId zoneId = ZoneOffset.UTC;
    private boolean taskSeeingTargetTime;
    private boolean globalMdc;

    public ProgrammableClock withMdc() {
        mdc = true;
        return this;
    }

    public ProgrammableClock withGlobalMdc(boolean globalMdc) {
        this.globalMdc = globalMdc;
        return this;
    }

    public ProgrammableClock withTimeZone(ZoneId zoneId) {
        this.zoneId = zoneId;
        return this;
    }

    public ProgrammableClock withTasksSeeingTargetTime(boolean taskSeeingTargetTime) {
        this.taskSeeingTargetTime = taskSeeingTargetTime;
        return this;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public void tick() {
        // execute due tasks
        checkState(currentTaskTime == null, "ticking from inside a task is not supported");
        Instant targetTime = currentTime;
        boolean tasksDue = !tasksByTriggerTime.isEmpty();
        while (tasksDue) {
            Map.Entry<Instant, List<Task>> earliestTaskEntry = tasksByTriggerTime.firstEntry();
            tasksDue = earliestTaskEntry != null && !earliestTaskEntry.getKey().isAfter(targetTime);
            if (tasksDue) {
                // this entry is due - run tasks
                new ArrayList<>(earliestTaskEntry.getValue()).forEach(Task::run);
            }
        }
    }

    public void setTimeAndTick(Instant time) {
        setTime(time);
        tick();
    }

    public void advanceTimeAndTick(TemporalAmount increment) {
        advanceTime(increment);
        tick();
    }

    @Override
    public LocalDateTime currentDateTime() {
        return currentInstant().atZone(zoneId).toLocalDateTime();
    }

    @Override
    public Instant currentInstant() {
        return currentTaskTime == null || taskSeeingTargetTime ? currentTime : currentTaskTime;
    }

    @Override
    public long nanoTime() {
        var currentInstant = currentInstant();
        return SECONDS.toNanos(currentInstant().getEpochSecond()) + currentInstant.getNano();
    }

    @Override
    public SchedulingExecutor createSingleThreadedSchedulingExecutor(String name, String family, int maxQueueSize) {
        // Deterministic test executor: runs tasks under this clock's control, so the queue bound and metric family are irrelevant.
        return new DeterministicExecutor(this, name);
    }

    Closeable schedule(DeterministicExecutor executor, TemporalAmount delay, Runnable command) {
        return scheduleOneTime(executor, delay, command, false);
    }

    /// Submits an `execute`/`submit` task — the deterministic equivalent of a live executor's immediate task. Immediate tasks are the ones [#closeExecutor]
    /// drains; delayed ones are discarded, mirroring [SingleThreadedSchedulingExecutor].
    void executeImmediate(DeterministicExecutor executor, Runnable command) {
        scheduleOneTime(executor, Duration.ZERO, command, true);
    }

    private Closeable scheduleOneTime(DeterministicExecutor executor, TemporalAmount delay, Runnable command, boolean immediate) {
        Task task = new Task(executor, immediate) {
            @Override
            public void doRun() {
                // One-time: dropped even when it throws, as a live executor drops it.
                try {
                    command.run();
                } finally {
                    unSchedule();
                }
            }

            @Override
            public String toString() {
                return (immediate ? "Immediate: " : "Delayed(" + delay + "): ") + command;
            }
        };
        task.schedule(dueAfter(delay));
        return task;
    }

    Closeable scheduleAtFixedRate(DeterministicExecutor executor, TemporalAmount initialDelay, TemporalAmount period, Runnable command) {
        // a live executor rejects such a period too
        checkArgument(Duration.from(period).isPositive(), "period must be positive, was %s", period);
        Task task = new Task(executor, false) {
            @Override
            public void doRun() {
                // Rescheduled even when it throws, as a live executor keeps a periodic task's rate.
                try {
                    command.run();
                } finally {
                    Instant wasDue = unSchedule();
                    if (wasDue != null) {
                        schedule(wasDue.plus(period));
                    } else {
                        // task closed itself
                    }
                }
            }

            @Override
            public String toString() {
                return "Periodic(" + initialDelay + "," + period + ": " + command;
            }
        };
        task.schedule(dueAfter(initialDelay));
        return task;
    }

    /// A negative delay is due now, as a live executor runs it at once.
    private Instant dueAfter(TemporalAmount delay) {
        Instant now = currentInstant();
        Instant due = now.plus(delay);
        return due.isBefore(now) ? now : due;
    }

    /// Mirrors [SingleThreadedSchedulingExecutor#close()]: runs `executor`'s immediate-task backlog to completion — including the immediate follow-ups those
    /// tasks submit — then discards work scheduled on it that has yet to fire, matching what a live executor does on shutdown.
    void closeExecutor(DeterministicExecutor executor) {
        for (Task dueTask = nextDueImmediateTaskOf(executor); dueTask != null; dueTask = nextDueImmediateTaskOf(executor)) {
            dueTask.run();
        }
        removeTasksOf(executor);
    }

    /// Locates the next task for [#closeExecutor] to drain. Running a task mutates [#tasksByTriggerTime], so the search completes and hands the task back
    /// before it runs.
    ///
    /// @return one of `executor`'s immediate tasks that is due now, or `null` once its immediate backlog is quiescent
    private @Nullable Task nextDueImmediateTaskOf(DeterministicExecutor executor) {
        for (List<Task> tasks : tasksByTriggerTime.headMap(currentInstant(), true).values()) {
            for (Task task : tasks) {
                if (task.immediate && task.executor == executor) {
                    return task;
                }
            }
        }
        return null;
    }

    private void removeTasksOf(DeterministicExecutor executor) {
        Iterator<List<Task>> taskIterator = tasksByTriggerTime.values().iterator();
        while (taskIterator.hasNext()) {
            List<Task> tasks = taskIterator.next();
            tasks.removeIf(task -> task.executor == executor);
            if (tasks.isEmpty()) {
                taskIterator.remove();
            }
        }
    }

    public void setTime(Instant currentTime) {
        checkArgument(currentTime.isAfter(this.currentTime) || currentTime.equals(this.currentTime),
                      "time cannot go backward from %s to %s", this.currentTime, currentTime);
        this.currentTime = checkNotNull(currentTime);
        if (globalMdc) {
            ThreadContext.put(MDC_CURRENT_TIME_KEY, currentTime.toString());
        }
    }

    public void advanceTime(TemporalAmount increment) {
        setTime(currentTime.plus(increment));
    }

    private abstract class Task extends BaseIdempotentCloseable implements Runnable {
        private final DeterministicExecutor executor;
        /// `true` for an `execute`/`submit` task, `false` for one scheduled with a delay — the distinction [#closeExecutor] drains on and discards on
        /// respectively, mirroring the immediate/scheduled split in [SingleThreadedSchedulingExecutor].
        private final boolean immediate;
        protected @Nullable Instant due;
        /// How many tasks precede this one in its chain at [#due]; running fails once it reaches [#MAX_TASK_CHAIN_AT_ONE_INSTANT].
        private int precedingTaskCount;

        protected Task(DeterministicExecutor executor, boolean immediate) {
            this.executor = checkNotNull(executor);
            this.immediate = immediate;
        }

        @Override
        public final void run() {
            if (due == null) {
                // un-scheduled
                return;
            }
            if (precedingTaskCount >= MAX_TASK_CHAIN_AT_ONE_INSTANT) {
                Instant chainInstant = unSchedule();
                throw new IllegalStateException(precedingTaskCount + " tasks in a row each scheduled the next for " + chainInstant
                                                + ", which keeps a live executor's thread busy without end; the next: " + this);
            }
            String oldCurrentTimeAttr = null;
            String oldThreadAttr = null;
            String oldTaskDueAttr = null;
            if (mdc) {
                oldCurrentTimeAttr = ThreadContext.get(MDC_CURRENT_TIME_KEY);
                oldThreadAttr = ThreadContext.get(MDC_THREAD_KEY);
                oldTaskDueAttr = ThreadContext.get(MDC_TASK_DUE_KEY);
                ThreadContext.put(MDC_THREAD_KEY, executor.getThreadNameBase());
                ThreadContext.put(MDC_TASK_DUE_KEY, due.toString());
                ThreadContext.put(MDC_CURRENT_TIME_KEY, currentInstant().toString());
            }
            Instant previousTaskTime = currentTaskTime;
            Task previousTask = currentTask;
            currentTaskTime = due;
            currentTask = this;
            try {
                doRun();
            } finally {
                currentTask = previousTask;
                currentTaskTime = previousTaskTime;
                if (mdc) {
                    restoreMdc(MDC_CURRENT_TIME_KEY, oldCurrentTimeAttr);
                    restoreMdc(MDC_TASK_DUE_KEY, oldTaskDueAttr);
                    restoreMdc(MDC_THREAD_KEY, oldThreadAttr);
                }
            }
        }

        private static void restoreMdc(String key, @Nullable String value) {
            if (value == null) {
                ThreadContext.remove(key);
            } else {
                ThreadContext.put(key, value);
            }
        }

        public void schedule(Instant due) {
            checkState(this.due == null, "already scheduled");
            this.due = due;
            precedingTaskCount = currentTask != null && due.equals(currentTaskTime) ? currentTask.precedingTaskCount + 1 : 0;
            tasksByTriggerTime.computeIfAbsent(due, _ -> new ArrayList<>()).add(this);
        }

        public final Instant unSchedule() {
            if (due != null) {
                tasksByTriggerTime.compute(due, (_, tasks) -> {
                    if (tasks != null) { // can be null if the executor was shut down in the meantime, which eventually removes all its tasks
                        tasks.remove(this);
                        if (tasks.isEmpty()) {
                            return null;
                        }
                    }
                    return tasks;
                });
            }
            Instant wasDue = due;
            due = null;
            return wasDue;
        }

        protected abstract void doRun();

        @Override
        protected final void doClose() {
            if (due != null) {
                unSchedule();
            }
        }
    }
}
