package net.yudichev.jiotty.common.async;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.ThreadContext;

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
    private final NavigableMap<Instant, List<Task>> tasksByTriggerTime = new TreeMap<>();
    private Instant currentTime = Instant.EPOCH;
    private boolean mdc;
    @Nullable
    private Instant currentTaskTime;
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
    public SchedulingExecutor createSingleThreadedSchedulingExecutor(String threadNameBase) {
        return new DeterministicExecutor(this, threadNameBase);
    }

    Closeable schedule(DeterministicExecutor executor, TemporalAmount delay, Runnable command) {
        Task task = new Task(executor) {
            @Override
            public void doRun() {
                command.run();
                unSchedule(); // one-time task
            }

            @Override
            public String toString() {
                return "Delayed(" + delay + "): " + command;
            }
        };
        task.schedule(currentInstant().plus(delay));
        return task;
    }

    Closeable scheduleAtFixedRate(DeterministicExecutor executor, TemporalAmount initialDelay, TemporalAmount period, Runnable command) {
        Task task = new Task(executor) {
            @Override
            public void doRun() {
                command.run();
                Instant wasDue = unSchedule();
                if (wasDue != null) {
                    schedule(wasDue.plus(period));
                } else {
                    // task closed itself
                }
            }

            @Override
            public String toString() {
                return "Periodic(" + initialDelay + "," + period + ": " + command;
            }
        };
        task.schedule(currentInstant().plus(initialDelay));
        return task;
    }

    void closeExecutor(DeterministicExecutor executor) {
        // simulate taking 1 second to close the executor completely;
        schedule(executor, Duration.ofSeconds(1), () -> removeTasksOf(executor));
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
            ThreadContext.put("current.time", currentTime.toString());
        }
    }

    public void advanceTime(TemporalAmount increment) {
        setTime(currentTime.plus(increment));
    }

    private abstract class Task extends BaseIdempotentCloseable implements Runnable {
        private final DeterministicExecutor executor;
        @Nullable
        protected Instant due;

        protected Task(DeterministicExecutor executor) {
            this.executor = checkNotNull(executor);
        }

        @Override
        public final void run() {
            if (due == null) {
                // un-scheduled
                return;
            }
            String oldCurrentTimeAttr = null;
            if (mdc) {
                oldCurrentTimeAttr = ThreadContext.get("current.time");
                ThreadContext.put("thread", executor.getThreadNameBase());
                ThreadContext.put("task.due", due.toString());
                ThreadContext.put("current.time", currentInstant().toString());
            }
            currentTaskTime = due;
            try {
                doRun();
            } finally {
                currentTaskTime = null;
                if (mdc) {
                    if (oldCurrentTimeAttr == null) {
                        ThreadContext.remove("current.time");
                    } else {
                        ThreadContext.put("current.time", oldCurrentTimeAttr);
                    }
                    ThreadContext.remove("task.due");
                    ThreadContext.remove("thread");
                }
            }
        }

        public void schedule(Instant due) {
            checkState(this.due == null, "already scheduled");
            this.due = due;
            tasksByTriggerTime.computeIfAbsent(due, instant -> new ArrayList<>()).add(this);
        }

        public final Instant unSchedule() {
            if (due != null) {
                tasksByTriggerTime.compute(due, (duration, tasks) -> {
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
