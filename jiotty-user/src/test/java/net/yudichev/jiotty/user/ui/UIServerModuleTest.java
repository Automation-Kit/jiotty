package net.yudichev.jiotty.user.ui;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.adminalerts.LoggingAdminAlertServiceModule;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;

class UIServerModuleTest {
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private Injector injector;
    /// Null when [#setUp] failed before assigning it, which is the one path where [#tearDown] has nothing to stop.
    private @Nullable List<LifecycleComponent> components;

    /// Starts every component in binding order — the order [Application] starts them in — so the test exercises the install order inside [UIServerModule]
    /// rather than only its bindings.
    @BeforeEach
    void setUp() {
        injector = Guice.createInjector(new ExecutorModule(),
                                        new TimeModule(),
                                        new AbstractModule() {
                                            @Override
                                            protected void configure() {
                                                bind(MeterRegistry.class).toInstance(new NoopMeterRegistry());
                                                bind(VarStore.class).toInstance(new InMemoryVarStore());
                                            }
                                        },
                                        UIServerModule.builder()
                                                      .setAdminAlertService(exposedBy(LoggingAdminAlertServiceModule.builder().build()))
                                                      .withThreadNameSuffix(literally("test-user"))
                                                      .build());
        components = injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                             .stream()
                             .map(binding -> injector.getInstance(binding.getKey()))
                             .toList();
        components.forEach(LifecycleComponent::start);
    }

    @AfterEach
    void tearDown() {
        if (components != null) {
            components.reversed().forEach(LifecycleComponent::stop);
        }
    }

    /// The push-device store resolves its executor when it starts, so the executor's own component has to have started first. Driving a store call through
    /// that executor pins the install order: a store registered ahead of its executor fails here rather than in production.
    @Test
    void startsThePushDeviceStoreOnAWorkingExecutor() {
        assertThat(injector.getInstance(PushDeviceStore.class).list()).succeedsWithin(CALL_TIMEOUT)
                                                                      .asInstanceOf(LIST)
                                                                      .isEmpty();
    }
}
