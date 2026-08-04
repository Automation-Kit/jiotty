package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProgrammableClockTest {

    @Mock
    private Runnable task;
    @Mock
    private Callable<String> callableTask;
    private ProgrammableClock clock;
    private SchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("threadName");
    }

    @Test
    void executeSimple() {
        executor.execute(task);
        verify(task, never()).run();

        clock.tick();
        verify(task).run();
    }

    @Test
    void executeNested() {
        executor.execute(() -> executor.execute(task));
        verify(task, never()).run();

        clock.tick();
        verify(task).run();
    }

    @Test
    void submitSimpleCallable() throws Exception {
        var resultFuture = executor.submit(callableTask);
        assertThat(resultFuture.isDone(), is(false));
        verify(callableTask, never()).call();

        when(callableTask.call()).thenReturn("result");
        clock.tick();
        assertThat(resultFuture.getNow(null), is("result"));
    }

    @Test
    void submitFailingCallable() throws Exception {
        var resultFuture = executor.submit(callableTask);

        when(callableTask.call()).thenThrow(new RuntimeException("oops"));
        clock.tick();

        assertThat(assertThrows(Throwable.class, () -> resultFuture.getNow(null)).getMessage(), containsString("oops"));
    }

    @Test
    void submitSimpleRunnable() throws Exception {
        var resultFuture = executor.submit(task);
        assertThat(resultFuture.isDone(), is(false));
        verify(callableTask, never()).call();

        clock.tick();
        verify(task).run();
        assertThat(resultFuture.isDone() && !resultFuture.isCompletedExceptionally(), is(true));
    }

    @Test
    void submitFailingRunnable() {
        var resultFuture = executor.submit(task);

        doThrow(new RuntimeException("oops")).when(task).run();
        clock.tick();

        assertThat(assertThrows(Throwable.class, () -> resultFuture.getNow(null)).getMessage(), containsString("oops"));
    }

    @Test
    void nanoTime() {
        assertThat(clock.nanoTime(), is(0L));
        clock.advanceTimeAndTick(Duration.ofSeconds(5));
        assertThat(clock.nanoTime(), is(SECONDS.toNanos(5)));
    }

    @Test
    void scheduleSimple() {
        executor.schedule(Duration.ofSeconds(1), task);
        verify(task, never()).run();

        clock.tick();
        verify(task, never()).run();

        clock.advanceTimeAndTick(Duration.ofMillis(500));
        verify(task, never()).run();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task).run();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verifyNoMoreInteractions(task);
    }

    @Test
    void scheduleNestedInExecute() {
        executor.execute(() -> executor.schedule(Duration.ofSeconds(1), task));
        verify(task, never()).run();

        clock.tick();
        verify(task, never()).run();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task).run();
    }

    @Test
    void executeNestedInSchedule() {
        executor.schedule(Duration.ofSeconds(1), () -> executor.execute(task));
        verify(task, never()).run();

        clock.tick();
        verify(task, never()).run();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task).run();
    }

    @Test
    void scheduleSimpleDoesNotFireIfClosed() {
        Closeable schedule = executor.schedule(Duration.ofSeconds(1), task);
        clock.tick();

        schedule.close();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task, never()).run();
    }

    @Test
    void scheduleAtFixedRateSimple() {
        Closeable schedule = executor.scheduleAtFixedRate(Duration.ofSeconds(3), Duration.ofSeconds(1), task);
        clock.tick();
        verify(task, never()).run();

        clock.setTimeAndTick(Instant.ofEpochMilli(2500));
        verify(task, never()).run();

        clock.setTimeAndTick(Instant.ofEpochMilli(3000));
        verify(task).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(3500));
        verify(task, never()).run();

        clock.setTimeAndTick(Instant.ofEpochMilli(4000));
        verify(task).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(4500));
        verify(task, never()).run();

        clock.setTimeAndTick(Instant.ofEpochMilli(5000));
        verify(task).run();
        reset(task);

        schedule.close();
        clock.setTimeAndTick(Instant.ofEpochMilli(7000));
        verify(task, never()).run();
    }

    @Test
    void scheduleAtFixedRateDeeplyNested() {
        /*
          0000 root   : schedule inner 1 every 1000
          1000 inner 1: schedule task 1 every 500
          1500 task 1 : RUN
          2000 inner 1: schedule task 2 every 500
               task 1 : RUN
          2500 task 1 : RUN
               task 2 : RUN
          3000 inner 1: schedule task 3 every 500
               task 1 : RUN
               task 2 : RUN
          3500 task 1 : RUN
               task 2 : RUN
               task 3 : RUN
         */
        executor.execute(() -> executor.scheduleAtFixedRate(Duration.ofSeconds(1),
                                                            () -> executor.execute(() -> executor.scheduleAtFixedRate(Duration.ofMillis(500), task))));

        clock.setTimeAndTick(Instant.ofEpochMilli(1000));
        verify(task, never()).run();

        clock.setTimeAndTick(Instant.ofEpochMilli(1500));
        verify(task).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(2000));
        verify(task).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(2500));
        verify(task, times(2)).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(3000));
        verify(task, times(2)).run();
        reset(task);

        clock.setTimeAndTick(Instant.ofEpochMilli(3500));
        verify(task, times(3)).run();
    }

    @Test
    void scheduleAtFixedRateWithInitialDelayZero() {
        var runTimes = new ArrayList<Instant>();
        executor.submit(() -> executor.scheduleAtFixedRate(Duration.ZERO, Duration.ofSeconds(1), () -> runTimes.add(clock.currentInstant())));
        clock.tick();
        assertThat(runTimes, contains(Instant.ofEpochSecond(0)));
        runTimes.clear();
        clock.setTimeAndTick(Instant.ofEpochMilli(2000));
        assertThat(runTimes, contains(Instant.ofEpochSecond(1), Instant.ofEpochSecond(2)));
    }

    @Test
    void nestedClosingOfScheduledTask() {
        Closeable schedule = executor.schedule(Duration.ofSeconds(2), task);
        executor.schedule(Duration.ofSeconds(1), schedule::close);

        clock.advanceTimeAndTick(Duration.ofSeconds(3));
        verify(task, never()).run();
    }

    @Test
    void scheduleSingleTaskAfterFewFixedRateTasks_executesAllInRightOrder(@Mock Runnable task2) {
        executor.scheduleAtFixedRate(Duration.ofSeconds(1), task);
        executor.schedule(Duration.ofSeconds(3), task2);

        clock.advanceTimeAndTick(Duration.ofSeconds(3));
        InOrder inOrder = inOrder(task, task2);
        inOrder.verify(task, times(2)).run();
        inOrder.verify(task2).run();
        inOrder.verify(task).run();
    }

    @Test
    void closeScheduledTaskAfterItsRun() {
        Closeable schedule = executor.schedule(Duration.ofSeconds(1), task);
        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        schedule.close();
    }

    @Test
    void closeDrainsImmediateBacklogWithoutTick() {
        executor.execute(task);

        executor.close();

        verify(task).run();
    }

    @Test
    void closeDrainsImmediateFollowUpsSubmittedByDrainingTasks(@Mock Runnable followUp) {
        executor.execute(() -> executor.execute(followUp));

        executor.close();

        verify(followUp).run();
    }

    @Test
    void closeDrainsOnlyItsOwnExecutorsBacklog(@Mock Runnable otherExecutorTask) {
        SchedulingExecutor executor2 = clock.createSingleThreadedSchedulingExecutor("executor2");
        executor.execute(task);
        executor2.execute(otherExecutorTask);

        executor2.close();

        verify(otherExecutorTask).run();
        verify(task, never()).run();
    }

    @Test
    void closeDiscardsAZeroDelayScheduledTask() {
        // Zero delay makes it due at the instant the drain examines, and close discards scheduled work
        executor.schedule(Duration.ZERO, task);

        executor.close();

        verify(task, never()).run();
    }

    @Test
    void submitAfterCloseIsRejected() {
        executor.close();

        assertThrows(IllegalStateException.class, () -> executor.execute(task));
    }

    @Test
    void closeDiscardsDelayedTaskScheduledWhileDraining() {
        executor.execute(() -> executor.schedule(Duration.ofSeconds(1), task));

        executor.close();

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task, never()).run();
    }

    @Test
    void closingOneExecutorCancelsItsTasks_sameTime(@Mock Runnable task2) {
        SchedulingExecutor executor2 = clock.createSingleThreadedSchedulingExecutor("executor2");
        executor.schedule(Duration.ofSeconds(2), task);
        executor2.schedule(Duration.ofSeconds(2), task2);
        clock.tick();

        executor2.close();

        clock.advanceTimeAndTick(Duration.ofSeconds(2));
        verify(task).run();
        verify(task2, never()).run();
    }

    @Test
    void closingOneExecutorCancelsItsTasks_diffTime(@Mock Runnable task2) {
        SchedulingExecutor executor2 = clock.createSingleThreadedSchedulingExecutor("executor2");
        executor.schedule(Duration.ofSeconds(2), task);
        executor2.schedule(Duration.ofSeconds(3), task2);
        clock.tick();

        executor2.close();

        clock.advanceTimeAndTick(Duration.ofSeconds(3));
        verify(task).run();
        verify(task2, never()).run();
    }

    @Test
    void oneTimeScheduledTaskClosesItself() {
        new Object() {
            private Closeable schedule;

            {
                schedule = executor.schedule(Duration.ofSeconds(1), () -> {
                    task.run();
                    schedule.close();
                });
                clock.advanceTimeAndTick(Duration.ofSeconds(1));
                verify(task).run();
            }
        };
    }

    @Test
    void periodicScheduledTaskClosesItself() {
        new Object() {
            private Closeable schedule;

            {
                schedule = executor.scheduleAtFixedRate(Duration.ofSeconds(1), Duration.ofSeconds(1), () -> {
                    task.run();
                    schedule.close();
                });
                clock.advanceTimeAndTick(Duration.ofSeconds(10));
                verify(task).run();
            }
        };
    }

    @Test
    void periodicTaskClosesScheduledTaskThatIsDueAtSameTime() {
        new Object() {
            private Closeable delayedSchedule;

            {
                executor.scheduleAtFixedRate(Duration.ofSeconds(1), () -> delayedSchedule.close());
                delayedSchedule = executor.schedule(Duration.ofSeconds(1), task);
            }
        };

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(task, never()).run();
    }
}