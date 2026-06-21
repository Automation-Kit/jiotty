package net.yudichev.jiotty.common.async;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.Test;

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
}
