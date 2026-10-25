package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceForecastSourcesModuleTest {
    @Test
    void build_default_exposesSourcesUnderBareKey() {
        ExposedKeyModule<List<PriceForecastSource>> module = PriceForecastSourcesModule.builder().build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(new TypeLiteral<List<PriceForecastSource>>() {}));
        Injector injector = Guice.createInjector(TimeModule.builder().build(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }

    @Test
    void build_withAnnotationAndUserAgent_exposesAnnotatedKey() {
        ExposedKeyModule<List<PriceForecastSource>> module = PriceForecastSourcesModule.builder()
                                                                                       .withUserAgent(literally("Example/1.0"))
                                                                                       .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                       .build();

        assertThat(module.getExposedKey()).isNotEqualTo(Key.get(new TypeLiteral<List<PriceForecastSource>>() {}));
        Injector injector = Guice.createInjector(TimeModule.builder().build(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }

    // The provider refuses a blank User-Agent, so failing to provision it proves the configured value is the one it receives.
    @Test
    void build_withUserAgent_handsTheValueToTheProvider() {
        Injector injector = Guice.createInjector(TimeModule.builder().build(), PriceForecastSourcesModule.builder().withUserAgent(literally(" ")).build());

        assertThatThrownBy(() -> injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                                         .forEach(binding -> injector.getInstance(binding.getKey())))
                .isInstanceOf(ProvisionException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
