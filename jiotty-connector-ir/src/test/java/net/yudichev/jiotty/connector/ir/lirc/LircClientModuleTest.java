package net.yudichev.jiotty.connector.ir.lirc;

import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class LircClientModuleTest {
    @Test
    void injector() {
        var module = LircClientModule.builder().build();
        assertThat(module.getExposedKey()).isEqualTo(Key.get(LircClient.class));
        Guice.createInjector(ExecutorModule.builder().build(), module).getBinding(module.getExposedKey());
    }
}