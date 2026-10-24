package net.yudichev.jiotty.common.async;

import com.google.inject.Guice;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorProviderModuleTest {
    @Test
    void providesAnExecutorRegisteredForStart() {
        var module = ExecutorProviderModule.builder().setThreadName(literally("test-executor")).build();
        var injector = Guice.createInjector(ExecutorModule.builder().build(), module);

        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
        assertThat(injector.getExistingBinding(module.getExposedKey())).isNotNull();
    }
}
