package net.yudichev.jiotty.logging;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class PersistingLog4jLevelConfiguratorModuleTest {
    @Test
    void configure() {
        ExposedKeyModule<LoggingLevelConfigurator> module = PersistingLog4jLevelConfiguratorModule.builder()
                                                                                                  .setVarStore(literally(new InMemoryVarStore()))
                                                                                                  .build();
        Guice.createInjector(module)
             .getBinding(module.getExposedKey());
    }
}
