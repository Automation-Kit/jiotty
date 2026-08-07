package net.yudichev.jiotty.connector.octopusenergy;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

class OctopusEnergyModuleTest {
    @Test
    void configure() {
        var module = OctopusEnergyModule.builder().build();
        Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), module).getBinding(module.getExposedKey());
    }
}