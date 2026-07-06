package net.yudichev.jiotty.common.lang.throttling;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ThrottlingConsumerTest {
    private static final Duration THROTTLING_PERIOD = Duration.ofMillis(100);

    private ProgrammableClock clock;
    private List<String> delivered;
    private ThrottlingConsumer<String> throttlingConsumer;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        delivered = new ArrayList<>();
        throttlingConsumer = new ThrottlingConsumer<>(executor, THROTTLING_PERIOD, delivered::add);
    }

    @Test
    void deliversFirstValueImmediately() {
        throttlingConsumer.accept("one");
        clock.tick();

        assertThat(delivered).containsExactly("one");
    }

    @Test
    void coalescesValuesWithinThrottlingPeriodAndDeliversLastWhenItElapses() {
        throttlingConsumer.accept("one");
        clock.tick();
        throttlingConsumer.accept("two");
        clock.tick();
        throttlingConsumer.accept("three");
        clock.tick();
        assertThat(delivered).containsExactly("one");

        clock.advanceTimeAndTick(THROTTLING_PERIOD);
        assertThat(delivered).containsExactly("one", "three");
    }

    @Test
    void suppressesDeliveryQueuedBeforeCloseButRunByExecutorAfterClose() {
        // accept() queues the delivery on the executor; it has not run yet because the clock has not ticked. Closing before the queued task runs must cancel
        //  the delivery, mirroring component teardown where the throttle is closed while a delivery still sits on the executor that later drains it.
        throttlingConsumer.accept("one");
        throttlingConsumer.close();
        clock.tick();

        assertThat(delivered).isEmpty();
    }

    @Test
    void doesNotDeliverPendingValueWhenTimerDrainsAfterClose() {
        throttlingConsumer.accept("one");
        clock.tick();
        throttlingConsumer.accept("two"); // held back until the throttle timer fires
        clock.tick();
        assertThat(delivered).containsExactly("one");

        // Advance to the throttle timer's due time without running it, then close: the timer task is now due but still queued, exactly as when the executor
        //  drains a due timer after the throttle has been closed. Delivery must be suppressed and the drain must not throw.
        clock.advanceTime(THROTTLING_PERIOD);
        throttlingConsumer.close();

        assertThatCode(clock::tick).doesNotThrowAnyException();
        assertThat(delivered).containsExactly("one");
    }
}
