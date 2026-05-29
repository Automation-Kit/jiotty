package net.yudichev.jiotty.common.metrics;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsModuleTest {
    @Test
    void bindings() {
        var injector = Guice.createInjector(MetricsModule.builder().build());

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {}))
                .as("bindings")
                .isNotEmpty();
    }
}
