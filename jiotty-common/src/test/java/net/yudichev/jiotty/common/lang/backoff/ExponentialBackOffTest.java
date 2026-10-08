package net.yudichev.jiotty.common.lang.backoff;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ExponentialBackOffTest {
    private static final Duration MAX_ELAPSED_TIME = Duration.ofSeconds(1);

    private final ProgrammableClock clock = new ProgrammableClock();

    @Test
    void fromOneMs() {
        var backOff = new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(1)
                .setMultiplier(1.5)
                .setMaxIntervalMillis(100)
                .setRandomizationFactor(0.05)
                .setMaxElapsedTimeMillis(Integer.MAX_VALUE)
                .build();
        for (int i = 0; i < 13; i++) {
            backOff.nextBackOffMillis();
        }
        assertThat((double) backOff.nextBackOffMillis()).isCloseTo(100, within(5.0));
    }

    @Test
    void aStreakThatOutlastsTheMaxElapsedTimeStops() {
        ExponentialBackOff backOff = createBackOffOnTheTestClock();
        backOff.nextBackOffMillis();

        clock.advanceTime(MAX_ELAPSED_TIME.plusMillis(1));

        assertThat(backOff.nextBackOffMillis()).isEqualTo(BackOff.STOP);
    }

    @Test
    void timeBeforeTheFirstFailureDoesNotCountTowardsTheMaxElapsedTime() {
        ExponentialBackOff backOff = createBackOffOnTheTestClock();

        clock.advanceTime(MAX_ELAPSED_TIME.multipliedBy(10));

        assertThat(backOff.nextBackOffMillis()).isNotEqualTo(BackOff.STOP);
        assertThat(backOff.getElapsedTimeMillis()).isZero();
    }

    @Test
    void resetEndsTheStreakSoAHealthySpellDoesNotCountTowardsTheMaxElapsedTime() {
        ExponentialBackOff backOff = createBackOffOnTheTestClock();
        backOff.nextBackOffMillis();
        backOff.reset();

        clock.advanceTime(MAX_ELAPSED_TIME.multipliedBy(10));

        assertThat(backOff.getElapsedTimeMillis()).isZero();
        assertThat(backOff.nextBackOffMillis()).isNotEqualTo(BackOff.STOP);
    }

    private ExponentialBackOff createBackOffOnTheTestClock() {
        return new ExponentialBackOff.Builder()
                .setMaxElapsedTimeMillis(MAX_ELAPSED_TIME.toMillis())
                .setNanoClock(clock)
                .build();
    }
}
