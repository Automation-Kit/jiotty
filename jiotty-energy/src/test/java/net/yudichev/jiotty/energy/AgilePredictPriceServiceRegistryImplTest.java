package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AgilePredictPriceServiceRegistryImplTest {

    @Mock
    private AgilePredictPriceService priceService;
    private AgilePredictPriceServiceRegistryImpl registry;

    @BeforeEach
    void setUp() {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        lenient().when(priceService.getPrices(any(), any(Integer.class))).thenReturn(completedFuture(List.of()));
        // Hand-rolled Factory mirrors what Guice's FactoryModuleBuilder would inject — keeps the test free of Guice setup.
        AgilePredictEnergyPriceServiceImpl.Factory factory = regionLetter ->
                new AgilePredictEnergyPriceServiceImpl(() -> executor, priceService, UpstreamHealthHandler.NO_OP, regionLetter);
        registry = new AgilePredictPriceServiceRegistryImpl(factory);
        registry.start();
    }

    @Test
    void forRegion_returnsSameInstanceOnRepeatCall() {
        EnergyPriceService first = registry.forRegion('A');
        EnergyPriceService second = registry.forRegion('A');
        assertThat(second).isSameAs(first);
    }

    @Test
    void forRegion_differentRegionsGetSeparateInstances() {
        EnergyPriceService a = registry.forRegion('A');
        EnergyPriceService b = registry.forRegion('B');
        assertThat(b).isNotSameAs(a);
    }
}
