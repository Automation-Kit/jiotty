package net.yudichev.jiotty.logging;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.varstore.VarStoreModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

class PersistingLog4jLevelConfiguratorModuleTest {
    @Test
    void configure() {
        var module = PersistingLog4jLevelConfiguratorModule.builder().build();
        Guice.createInjector(VarStoreModule.builder().setPath(Paths.get(System.getProperty("java.io.tmpdir"))).build(), module)
             .getBinding(module.getExposedKey());

    }
}