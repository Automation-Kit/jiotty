package net.yudichev.jiotty.common.observer;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ExecutorSubscriptionTest {
    private final ProgrammableClock clock = new ProgrammableClock();
    private final SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("owner");
    private final ExecutorSubscription subscription = new ExecutorSubscription(executor, "unsubscribe");
    private int closeCount;
    private final Closeable handle = () -> closeCount++;

    @Test
    void closingAfterTheHandleIsSetClosesItInATaskOnTheExecutor() {
        executor.execute("subscribe", () -> subscription.set(handle));
        clock.tick();

        subscription.close();
        assertThat(closeCount).isZero();
        clock.tick();

        assertThat(closeCount).isOne();
    }

    @Test
    void closingBeforeTheSubscribingTaskRunsClosesTheHandleThatTaskSets() {
        executor.execute("subscribe", () -> subscription.set(handle));

        subscription.close();
        clock.tick();

        assertThat(closeCount).isOne();
    }

    @Test
    void closingWhenNoHandleWasSetDoesNothing() {
        subscription.close();

        assertThatCode(clock::tick).doesNotThrowAnyException();
    }

    @Test
    void closingTwiceClosesTheHandleOnce() {
        executor.execute("subscribe", () -> subscription.set(handle));
        clock.tick();

        subscription.close();
        subscription.close();
        clock.tick();

        assertThat(closeCount).isOne();
    }

    @Test
    void closingAfterTheExecutorShutDownDoesNothing() {
        executor.execute("subscribe", () -> subscription.set(handle));
        clock.tick();
        executor.close();

        assertThatCode(subscription::close).doesNotThrowAnyException();
        assertThat(closeCount).isZero();
    }
}
