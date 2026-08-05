package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.metrics.NoopMeterRegistry;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PriceForecastServiceRegistryImplTest {

    @Mock
    private PriceForecastSource priceForecastSource;
    private PriceForecastServiceRegistryImpl registry;

    @BeforeEach
    void setUp() {
        var clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        lenient().when(priceForecastSource.name()).thenReturn("source");
        lenient().when(priceForecastSource.getPrices(any(), anyInt())).thenReturn(completedFuture(List.of()));
        // Hand-rolled Factory mirrors what Guice's FactoryModuleBuilder would inject — keeps the test free of Guice setup.
        ForecastEnergyPriceServiceImpl.Factory factory = regionLetter ->
                new ForecastEnergyPriceServiceImpl(() -> executor,
                                                   List.of(priceForecastSource),
                                                   RetryableOperationExecutor.noRetries(),
                                                   UpstreamHealthHandler.NO_OP,
                                                   new NoopMeterRegistry(),
                                                   Optional.empty(),
                                                   clock,
                                                   regionLetter);
        registry = new PriceForecastServiceRegistryImpl(factory);
        registry.start();
    }

    @Test
    void forRegion_returnsSameInstanceOnRepeatCall() {
        ForecastEnergyPriceService first = registry.forRegion('A');
        ForecastEnergyPriceService second = registry.forRegion('A');
        assertThat(second).isSameAs(first);
    }

    @Test
    void forRegion_differentRegionsGetSeparateInstances() {
        ForecastEnergyPriceService a = registry.forRegion('A');
        ForecastEnergyPriceService b = registry.forRegion('B');
        assertThat(b).isNotSameAs(a);
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.stop();
        }
    }
}
