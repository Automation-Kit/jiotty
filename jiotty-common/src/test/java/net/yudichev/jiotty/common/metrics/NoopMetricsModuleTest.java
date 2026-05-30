package net.yudichev.jiotty.common.metrics;

import com.google.inject.Guice;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopMetricsModuleTest {
    @Test
    void bindsRegistryThatDiscardsMeasurements() {
        MeterRegistry registry = Guice.createInjector(new NoopMetricsModule()).getInstance(MeterRegistry.class);

        registry.counter("test.counter").increment(5.0);

        // An empty composite has no backing registries, so the increment is dropped rather than retained.
        assertThat(registry.counter("test.counter").count()).isEqualTo(0.0);
    }
}
