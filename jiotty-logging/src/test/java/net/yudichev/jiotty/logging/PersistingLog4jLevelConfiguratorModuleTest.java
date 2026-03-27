package net.yudichev.jiotty.logging;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;

class PersistingLog4jLevelConfiguratorModuleTest {
    @Test
    void configure() {
        var module = PersistingLog4jLevelConfiguratorModule.builder().build();
        Guice.createInjector(new AbstractModule() {
                                 @Override
                                 protected void configure() {
                                     bind(VarStore.class).toInstance(new InMemoryVarStore());
                                 }
                             },
                             module)
             .getBinding(module.getExposedKey());
    }
}