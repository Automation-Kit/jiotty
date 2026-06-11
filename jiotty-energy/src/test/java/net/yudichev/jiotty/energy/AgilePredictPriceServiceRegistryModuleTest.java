package net.yudichev.jiotty.energy;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class AgilePredictPriceServiceRegistryModuleTest {
    @Test
    void build_default_exposesRegistryUnderBareKey() {
        ExposedKeyModule<AgilePredictPriceServiceRegistry> module = AgilePredictPriceServiceRegistryModule.builder().build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(AgilePredictPriceServiceRegistry.class));
        Injector injector = Guice.createInjector(new ExecutorModule(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }

    @Test
    void build_withAnnotationAndCustomHandler_exposesAnnotatedKey() {
        ExposedKeyModule<AgilePredictPriceServiceRegistry> module =
                AgilePredictPriceServiceRegistryModule.builder()
                                                      .withStatusHandler(literally(new NoOpPriceRetrievalStatusHandler()))
                                                      .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                      .build();

        assertThat(module.getExposedKey()).isNotEqualTo(Key.get(AgilePredictPriceServiceRegistry.class));
        Injector injector = Guice.createInjector(new ExecutorModule(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }
}
