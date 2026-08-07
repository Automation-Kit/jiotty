package net.yudichev.jiotty.connector.ip;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

class HostMonitorModuleTest {
    @Test
    void bindings() {
        Named annotation = Names.named("ann");
        Injector injector = Guice.createInjector(
                ExecutorModule.builder().build(),
                TimeModule.builder().build(),
                HostMonitorModule.builder()
                                 .setHostnames(literally(List.of("host1", "host2")))
                                 .withName(literally("name"))
                                 .withTolerance(literally(Duration.ofSeconds(1)))
                                 .withAnnotation(forAnnotation(annotation))
                                 .build());
        injector.getBinding(Key.get(HostMonitor.class, annotation));
    }
}