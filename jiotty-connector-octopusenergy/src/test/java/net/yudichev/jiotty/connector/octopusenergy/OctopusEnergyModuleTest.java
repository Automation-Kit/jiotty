package net.yudichev.jiotty.connector.octopusenergy;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

class OctopusEnergyModuleTest {
    @Test
    void configure() {
        var module = OctopusEnergyModule.builder().build();
        Guice.createInjector(new TimeModule(), module).getBinding(module.getExposedKey());
    }
}