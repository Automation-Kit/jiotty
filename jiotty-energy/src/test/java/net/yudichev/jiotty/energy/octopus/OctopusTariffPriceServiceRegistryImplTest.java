package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.octopus.tariff.OctopusAgilePriceServiceImpl;
import net.yudichev.jiotty.energy.octopus.tariff.OctopusPublishedRatePriceServiceImpl;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.energy.octopus.TestProductDetails.createProductDetails;
import static net.yudichev.jiotty.energy.octopus.TestProductDetails.createVariants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusTariffPriceServiceRegistryImplTest {
    private static final String AGILE_TARIFF = "E-1R-AGILE-23-12-06-A";
    private static final String FLEXIBLE_PRODUCT = "VAR-22-11-01";
    private static final String FLEXIBLE_TARIFF = "E-1R-VAR-22-11-01-A";
    private static final String IOG_PRODUCT = "IOG-SMB-VAR-26-11-04";
    private static final String IOG_TARIFF = "E-1R-IOG-SMB-VAR-26-11-04-A";
    private static final String TRACKER_PRODUCT = "SILVER-24-12-31";
    private static final String TRACKER_TARIFF = "E-1R-SILVER-24-12-31-A";
    private static final ProductDetails FLEXIBLE_DETAILS = createProductDetails(FLEXIBLE_PRODUCT,
                                                                                "Flexible Octopus",
                                                                                false,
                                                                                createVariants(FLEXIBLE_TARIFF),
                                                                                ImmutableMap.of());

    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusRegionService regionServiceA;
    private final List<RateSchedule> createdRateSchedules = new ArrayList<>();
    private ProgrammableClock clock;
    private SchedulingExecutor executor;
    private OctopusTariffPriceServiceRegistryImpl registry;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        executor = clock.createSingleThreadedSchedulingExecutor("test");
        var cache = new InMemoryTimeSeriesCache();
        lenient().when(octopusEnergy.region('A')).thenReturn(regionServiceA);
        lenient().when(regionServiceA.getStandardUnitRates(any(), any(), any(), any())).thenReturn(completedFuture(List.of()));
        lenient().when(regionServiceA.getDayUnitRates(any(), any(), any(), any())).thenReturn(completedFuture(List.of()));
        lenient().when(regionServiceA.getNightUnitRates(any(), any(), any(), any())).thenReturn(completedFuture(List.of()));
        // This hand-rolled factory builds the same services as Guice's FactoryModuleBuilder, so the test needs no Guice setup.
        var factory = new OctopusTariffPriceServiceFactory() {
            @Override
            public OctopusAgilePriceServiceImpl createAgile(OctopusRegionService regionService, String productCode, String tariffCode) {
                return new OctopusAgilePriceServiceImpl(() -> executor,
                                                        clock,
                                                        () -> 0.5,
                                                        cache,
                                                        new ListenerBackedTaskExceptionHandlerRegistry(),
                                                        regionService,
                                                        productCode,
                                                        tariffCode);
            }

            @Override
            public OctopusPublishedRatePriceServiceImpl createPublished(OctopusRegionService regionService,
                                                                        RateSchedule rateSchedule,
                                                                        String productCode,
                                                                        String tariffCode) {
                createdRateSchedules.add(rateSchedule);
                return new OctopusPublishedRatePriceServiceImpl(() -> executor,
                                                                clock,
                                                                () -> 0.5,
                                                                TestPrices.FORWARD_WINDOW,
                                                                new ListenerBackedTaskExceptionHandlerRegistry(),
                                                                regionService,
                                                                rateSchedule,
                                                                productCode,
                                                                tariffCode);
            }
        };
        registry = new OctopusTariffPriceServiceRegistryImpl(() -> executor, octopusEnergy, factory);
        registry.start();
    }

    @Test
    void forTariff_returnsSameInstanceOnRepeatCall() {
        TariffPriceSource first = resolve(AGILE_TARIFF);
        TariffPriceSource second = resolve(AGILE_TARIFF);
        assertThat(second).isSameAs(first);
        // The registry obtains one region service per region, so octopusEnergy.region is called once.
        verify(octopusEnergy).region('A');
    }

    @Test
    void forTariff_agileTariff_isDynamicWithoutAProductLookup() {
        assertThat(resolve(AGILE_TARIFF)).isInstanceOfSatisfying(TariffPriceSource.Priced.class, priced -> {
            assertThat(priced.pricing()).isEqualTo(TariffPricing.DYNAMIC);
            assertThat(priced.supplierManagedCharging()).isFalse();
            assertThat(priced.service()).isInstanceOf(OctopusAgilePriceServiceImpl.class);
        });
        verify(octopusEnergy, times(0)).getProductDetails(any());
    }

    @Test
    void forTariff_differentProductsInSameRegionGetSeparateInstances() {
        OctopusTariffPriceService first = pricedService(resolve(AGILE_TARIFF));
        OctopusTariffPriceService second = pricedService(resolve("E-1R-AGILE-22-04-08-A"));
        assertThat(second).isNotSameAs(first);
    }

    @Test
    void forTariff_singleRegisterTariff_isPublishedFromTheStandardUnitRates() {
        when(octopusEnergy.getProductDetails(FLEXIBLE_PRODUCT)).thenReturn(completedFuture(FLEXIBLE_DETAILS));

        assertThat(resolve(FLEXIBLE_TARIFF)).isInstanceOfSatisfying(TariffPriceSource.Priced.class, priced -> {
            assertThat(priced.pricing()).isEqualTo(TariffPricing.PUBLISHED);
            assertThat(priced.supplierManagedCharging()).isFalse();
        });
        assertThat(createdRateSchedules).containsExactly(RateSchedule.STANDARD_UNIT_RATES);
    }

    @Test
    void forTariff_fourRateTariff_isPublishedFromTheDayAndNightRates_andSupplierManaged() {
        when(octopusEnergy.getProductDetails(IOG_PRODUCT))
                .thenReturn(completedFuture(createProductDetails(IOG_PRODUCT, "Intelligent Octopus Go", false, ImmutableMap.of(), createVariants(IOG_TARIFF))));

        assertThat(resolve(IOG_TARIFF)).isInstanceOfSatisfying(TariffPriceSource.Priced.class, priced -> {
            assertThat(priced.pricing()).isEqualTo(TariffPricing.PUBLISHED);
            assertThat(priced.supplierManagedCharging()).isTrue();
        });
        assertThat(createdRateSchedules).containsExactly(RateSchedule.DAY_AND_NIGHT_RATES);
    }

    @Test
    void forTariff_tracker_isUnsupported() {
        when(octopusEnergy.getProductDetails(TRACKER_PRODUCT))
                .thenReturn(completedFuture(createProductDetails(TRACKER_PRODUCT, "Octopus Tracker", true, createVariants(TRACKER_TARIFF), ImmutableMap.of())));

        assertThat(resolve(TRACKER_TARIFF)).isSameAs(TariffPriceSource.Unsupported.INSTANCE);
        assertThat(createdRateSchedules).isEmpty();
    }

    @Test
    void forTariff_failedProductLookup_failsAndIsTriedAgainOnTheNextCall() {
        when(octopusEnergy.getProductDetails(FLEXIBLE_PRODUCT))
                .thenReturn(failedFuture(new RuntimeException("octopus is down")))
                .thenReturn(completedFuture(FLEXIBLE_DETAILS));

        CompletableFuture<TariffPriceSource> firstSource = registry.forTariff(FLEXIBLE_TARIFF);
        clock.tick();
        assertThat(firstSource).isCompletedExceptionally();
        assertThat(resolve(FLEXIBLE_TARIFF)).isInstanceOf(TariffPriceSource.Priced.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"E-1R-AGILE-24-10-01-Z", "E-1R-SILVER-23-12-06-I", ""})
    void forTariff_codeNamingNoKnownRegion_failsWithoutCallingOctopus(String tariffCode) {
        assertThat(registry.forTariff(tariffCode)).failsWithin(Duration.ZERO)
                                                  .withThrowableOfType(ExecutionException.class)
                                                  .withCauseInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(octopusEnergy);
    }

    @Test
    void forTariff_registryStoppedBeforeTheClassificationEnds_neverCompletes() {
        CompletableFuture<ProductDetails> details = new CompletableFuture<>();
        when(octopusEnergy.getProductDetails(FLEXIBLE_PRODUCT)).thenReturn(details);
        CompletableFuture<TariffPriceSource> source = registry.forTariff(FLEXIBLE_TARIFF);

        registry.stop();
        details.complete(FLEXIBLE_DETAILS);
        clock.tick();

        assertThat(source).isNotDone();
        assertThat(createdRateSchedules).isEmpty();
    }

    @Test
    void forTariff_productDetailsArrivingAfterTheExecutorHasClosed_neverCompletes() {
        CompletableFuture<ProductDetails> details = new CompletableFuture<>();
        when(octopusEnergy.getProductDetails(FLEXIBLE_PRODUCT)).thenReturn(details);
        CompletableFuture<TariffPriceSource> source = registry.forTariff(FLEXIBLE_TARIFF);

        registry.stop();
        executor.close();
        details.complete(FLEXIBLE_DETAILS);
        clock.tick();

        assertThat(source).isNotDone();
    }

    @Test
    void stop_stopsEveryPricedService_andClosesRegionServices() {
        when(octopusEnergy.getProductDetails(FLEXIBLE_PRODUCT)).thenReturn(completedFuture(FLEXIBLE_DETAILS));
        when(octopusEnergy.getProductDetails(TRACKER_PRODUCT))
                .thenReturn(completedFuture(createProductDetails(TRACKER_PRODUCT, "Octopus Tracker", true, createVariants(TRACKER_TARIFF), ImmutableMap.of())));
        OctopusTariffPriceService agileService = pricedService(resolve(AGILE_TARIFF));
        OctopusTariffPriceService flexibleService = pricedService(resolve(FLEXIBLE_TARIFF));
        resolve(TRACKER_TARIFF);

        registry.stop();
        clock.tick();

        assertThatThrownBy(() -> agileService.subscribeToPrices(_ -> {})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> flexibleService.subscribeToPrices(_ -> {})).isInstanceOf(IllegalStateException.class);
        verify(regionServiceA).close();
    }

    private TariffPriceSource resolve(String tariffCode) {
        CompletableFuture<TariffPriceSource> source = registry.forTariff(tariffCode);
        clock.tick();
        assertThat(source).succeedsWithin(Duration.ZERO);
        return source.getNow(null);
    }

    private static OctopusTariffPriceService pricedService(TariffPriceSource source) {
        if (source instanceof TariffPriceSource.Priced(var service, _, _)) {
            return service;
        }
        throw new AssertionError("Expected a priced source, got " + source);
    }
}
