package net.yudichev.jiotty.connector.miele;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.varstore.VarStoreModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class MieleModuleTest {
    @Test
    void configures() {
        var module = MieleModule.builder()
                                .setDeviceId(literally("di"))
                                .setClientId(literally("ci"))
                                .setClientSecret(literally("cs"))
                                .build();
        Guice.createInjector(new TimeModule(), new ExecutorModule(), VarStoreModule.builder().setPath(Paths.get("p")).build(), module)
             .getBinding(MieleDishwasher.class);
    }
}