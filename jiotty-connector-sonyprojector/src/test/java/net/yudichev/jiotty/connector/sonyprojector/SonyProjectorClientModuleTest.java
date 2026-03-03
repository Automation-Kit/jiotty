package net.yudichev.jiotty.connector.sonyprojector;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class SonyProjectorClientModuleTest {
    @Test
    void configuresWithPassword() {
        var module = SonyProjectorClientModule.builder()
                                              .setHost(literally("host"))
                                              .withPort(literally(53595))
                                              .withTimeout(literally(Duration.ofSeconds(1)))
                                              .withPassword(literally("secret"))
                                              .build();
        Guice.createInjector(new ExecutorModule(), module).getBinding(SonyProjectorClient.class);
    }

    @Test
    void configuresWithoutPassword() {
        var module = SonyProjectorClientModule.builder()
                                              .setHost(literally("host"))
                                              .build();
        Guice.createInjector(new ExecutorModule(), module).getBinding(SonyProjectorClient.class);
    }
}
