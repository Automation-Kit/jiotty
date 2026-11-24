package net.yudichev.jiotty.energy.octopus;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.ExposedBinding;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class OctopusTariffPriceServiceRegistryModuleTest {
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private TimeSeriesCache cache;
    @Mock
    private JobScheduler jobScheduler;

    @Test
    void build_default_exposesTheRegistryUnderTheBareKey() {
        ExposedKeyModule<OctopusTariffPriceServiceRegistry> module = builder().build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(OctopusTariffPriceServiceRegistry.class));
        Injector injector = createInjector(module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    @Test
    void build_withAnnotation_exposesTheAnnotatedKey() {
        ExposedKeyModule<OctopusTariffPriceServiceRegistry> module = builder().withAnnotation(forAnnotation(uniqueAnnotation())).build();

        assertThat(module.getExposedKey()).isNotEqualTo(Key.get(OctopusTariffPriceServiceRegistry.class));
        assertThat(createInjector(module).getInstance(module.getExposedKey())).isNotNull();
    }

    @Test
    void build_refusesNoForwardWindow() {
        assertThatThrownBy(() -> OctopusTariffPriceServiceRegistryModule.builder().build()).isInstanceOf(NullPointerException.class)
                                                                                           .hasMessage("setForwardWindow was not called");
    }

    @Test
    void forwardWindow_reachesThePublishedRatePriceService(@Mock OctopusRegionService regionService) {
        ExposedKeyModule<OctopusTariffPriceServiceRegistry> module = OctopusTariffPriceServiceRegistryModule.builder()
                                                                                                            .setForwardWindow(literally(Duration.ZERO))
                                                                                                            .build();
        Injector injector = createInjector(module);
        Injector moduleInjector = ((ExposedBinding<?>) injector.getBinding(module.getExposedKey())).getPrivateElements().getInjector();
        OctopusTariffPriceServiceFactory factory = moduleInjector.getInstance(OctopusTariffPriceServiceFactory.class);

        assertThatThrownBy(() -> factory.createPublished(regionService, RateSchedule.STANDARD_UNIT_RATES, "VAR-22-11-01", "E-1R-VAR-22-11-01-A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("forwardWindow must be positive: PT0S");
    }

    private static OctopusTariffPriceServiceRegistryModule.Builder builder() {
        return OctopusTariffPriceServiceRegistryModule.builder().setForwardWindow(literally(TestPrices.FORWARD_WINDOW));
    }

    private Injector createInjector(ExposedKeyModule<OctopusTariffPriceServiceRegistry> module) {
        return Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(OctopusEnergy.class).toInstance(octopusEnergy);
                bind(TimeSeriesCache.class).toInstance(cache);
                bind(JobScheduler.class).toInstance(jobScheduler);
            }
        }, module);
    }
}
