package net.yudichev.jiotty.connector.miele;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class MieleModuleTest {
    @Test
    void configures() {
        var module = MieleModule.builder()
                                .setDeviceId(literally("di"))
                                .setClientId(literally("ci"))
                                .setClientSecret(literally("cs"))
                                .build();
        Guice.createInjector(TimeModule.builder().build(),
                             ExecutorModule.builder().build(),
                             new AbstractModule() {
                                 @Override
                                 protected void configure() {
                                     bind(VarStore.class).toInstance(new InMemoryVarStore());
                                 }
                             },
                             module)
             .getBinding(MieleDishwasher.class);
    }
}