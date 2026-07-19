package net.yudichev.jiotty.common.async;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static org.assertj.core.api.Assertions.assertThat;

class ExecutorModuleTest {
    @Test
    void injector() {
        var module = new ExecutorModule();
        Injector injector = Guice.createInjector(module);

        injector.getBinding(module.getExposedKey());
        // Must be a singleton: the executor injects the same registry instance that callers register handlers on, else registered handlers never see failures.
        assertThat(injector.getInstance(TaskExceptionHandlerRegistry.class)).isSameAs(injector.getInstance(TaskExceptionHandlerRegistry.class));
    }

    @Test
    void meterRegistryOptionallyReachesTheFactoryAcrossThePrivateModule() {
        var registry = new SimpleMeterRegistry();
        var module = new ExecutorModule();
        Injector injector = Guice.createInjector(module, binder -> binder.bind(MeterRegistry.class).toInstance(registry));

        SchedulingExecutor executor = injector.getInstance(module.getExposedKey()).createSingleThreadedSchedulingExecutor("wired", "wiredfam", 10);
        try {
            assertThat(registry.find("executor.queued").tags("name", "wired", "family", "wiredfam").gauge()).isNotNull();
        } finally {
            closeIfNotNull(executor);
        }
    }

    @Test
    void executorsAreCreatedUnmeteredWhenNoRegistryBound() {
        var module = new ExecutorModule();
        Injector injector = Guice.createInjector(module);

        // No MeterRegistry bound: the optional injection is skipped and executor creation still succeeds.
        closeIfNotNull(injector.getInstance(module.getExposedKey()).createSingleThreadedSchedulingExecutor("unwired"));
    }
}
