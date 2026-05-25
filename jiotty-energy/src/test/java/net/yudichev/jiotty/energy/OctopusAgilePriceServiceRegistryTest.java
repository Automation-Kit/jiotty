package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.JobSchedulerImpl;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OctopusAgilePriceServiceRegistryTest {

    private static final String PRODUCT = "AGILE-23-12-06";
    private static final String TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final String TARIFF_B = "E-1R-AGILE-22-04-08-A";

    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusRegionService regionServiceA;
    private OctopusAgilePriceServiceRegistry registry;

    @BeforeEach
    void setUp() {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        var jobScheduler = new JobSchedulerImpl(clock, clock, ZoneOffset.UTC);
        jobScheduler.start();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        var cache = new InMemoryTimeSeriesCache();
        lenient().when(octopusEnergy.region('A')).thenReturn(regionServiceA);
        lenient().when(regionServiceA.getStandardUnitRates(any(), any(), any(), any()))
                 .thenReturn(completedFuture(List.of()));
        // Hand-rolled Factory mirrors what Guice's FactoryModuleBuilder would inject — keeps the test free of Guice setup.
        OctopusAgilePriceServiceImpl.Factory factory = (regionService, productCode, tariffCode) ->
                new OctopusAgilePriceServiceImpl(() -> executor, clock, cache, jobScheduler, regionService, productCode, tariffCode);
        registry = new OctopusAgilePriceServiceRegistry(octopusEnergy, factory);
        registry.start();
    }

    @Test
    void forTariff_returnsSameInstanceOnRepeatCall() {
        EnergyPriceService first = registry.forTariff(PRODUCT, TARIFF_A);
        EnergyPriceService second = registry.forTariff(PRODUCT, TARIFF_A);
        assertThat(second).isSameAs(first);
        // Region service is interned per region; only one call.
        verify(octopusEnergy).region('A');
    }

    @Test
    void forTariff_differentProductsInSameRegionGetSeparateInstances() {
        EnergyPriceService a = registry.forTariff(PRODUCT, TARIFF_A);
        EnergyPriceService b = registry.forTariff("AGILE-22-04-08", TARIFF_B);
        assertThat(b).isNotSameAs(a);
    }

    @Test
    void forTariff_rejectsNonAgileProduct() {
        assertThatThrownBy(() -> registry.forTariff("GO-VAR-22-10-14", "E-1R-GO-VAR-22-10-14-A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected Agile product code");
    }

    @Test
    void stop_closesRegionServices() {
        registry.forTariff(PRODUCT, TARIFF_A);
        registry.stop();
        verify(regionServiceA).close();
    }
}
