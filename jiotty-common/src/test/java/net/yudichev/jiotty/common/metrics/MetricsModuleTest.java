package net.yudichev.jiotty.common.metrics;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsModuleTest {
    @Test
    void bindings() {
        var injector = Guice.createInjector(MetricsModule.builder().build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {}))
                .as("MetricsModule registers lifecycle components (JvmMetricsBinder, MetricsHttpHandler, internal JavalinRestServer)")
                .isNotEmpty();
        assertThat(injector.getInstance(MeterRegistry.class))
                .as("MeterRegistry is exposed for application instrumentation")
                .isNotNull();
        assertThat(injector.getInstance(PrometheusMeterRegistry.class))
                .as("PrometheusMeterRegistry is exposed for the scrape endpoint")
                .isNotNull();
        assertThat(injector.getInstance(MeterRegistry.class))
                .as("the same singleton is returned for both the abstract MeterRegistry and the concrete PrometheusMeterRegistry binding")
                .isSameAs(injector.getInstance(PrometheusMeterRegistry.class));
    }
}
