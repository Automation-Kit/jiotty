package net.yudichev.jiotty.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusMeterRegistryProviderTest {
    @Test
    void appliesNoCommonTagsWhenNoneConfigured() {
        var registry = new PrometheusMeterRegistryProvider(Tags.empty()).get();

        Counter counter = registry.counter("example");

        assertThat(counter.getId().getTags())
                .as("counter has no synthetic tags when no common tags were configured")
                .isEmpty();
    }

    @Test
    void attachesConfiguredCommonTagsToEveryMeter() {
        var registry = new PrometheusMeterRegistryProvider(Tags.of("application", "x", "env", "test")).get();

        Counter counter = registry.counter("a_counter");
        Timer timer = registry.timer("a_timer");

        assertThat(counter.getId().getTags())
                .as("counter inherits configured common tags")
                .contains(Tag.of("application", "x"), Tag.of("env", "test"));
        assertThat(timer.getId().getTags())
                .as("timer inherits configured common tags")
                .contains(Tag.of("application", "x"), Tag.of("env", "test"));
    }

    @Test
    void timerEmitsPercentileHistogramBuckets() {
        var registry = new PrometheusMeterRegistryProvider(Tags.empty()).get();

        Timer timer = registry.timer("a_timer");
        timer.record(Duration.ofMillis(50));

        assertThat(timer.takeSnapshot().histogramCounts())
                .as("Timer registered through the provided registry emits histogram buckets")
                .isNotEmpty();
    }

    @Test
    void counterIsNotAffectedByTheTimerHistogramFilter() {
        var registry = new PrometheusMeterRegistryProvider(Tags.empty()).get();

        Counter counter = registry.counter("a_counter");
        counter.increment();

        assertThat(counter.measure())
                .as("Counter registered through the provided registry only emits its single COUNT measurement, not histogram buckets")
                .hasSize(1);
    }
}
