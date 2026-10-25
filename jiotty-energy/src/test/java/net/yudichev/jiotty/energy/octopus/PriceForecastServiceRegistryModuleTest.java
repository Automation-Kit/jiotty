package net.yudichev.jiotty.energy.octopus;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceForecastServiceRegistryModuleTest {
    // The forecast sources refuse a blank User-Agent, so failing to provision them proves the module forwards the configured value.
    @Test
    void build_withUserAgent_forwardsTheValueToTheSources() {
        ExposedKeyModule<PriceForecastServiceRegistry> module = PriceForecastServiceRegistryModule.builder().withUserAgent(literally(" ")).build();
        Injector injector = Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), module);

        assertThatThrownBy(() -> injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                                         .forEach(binding -> injector.getInstance(binding.getKey())))
                .isInstanceOf(ProvisionException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_default_exposesRegistryUnderBareKey() {
        ExposedKeyModule<PriceForecastServiceRegistry> module = PriceForecastServiceRegistryModule.builder().build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(PriceForecastServiceRegistry.class));
        Injector injector = Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }

    @Test
    void build_withAnnotationAndCustomHandler_exposesAnnotatedKey() {
        ExposedKeyModule<PriceForecastServiceRegistry> module =
                PriceForecastServiceRegistryModule.builder()
                                                  .withStatusHandler(literally(UpstreamHealthHandler.NO_OP))
                                                  .withMeterRegistry(literally(new NoopMeterRegistry()))
                                                  .withVarStore(literally(new InMemoryVarStore()))
                                                  .withUserAgent(literally("Example/1.0"))
                                                  .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                  .build();

        assertThat(module.getExposedKey()).isNotEqualTo(Key.get(PriceForecastServiceRegistry.class));
        Injector injector = Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }
}
