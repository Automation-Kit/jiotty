package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceService;
import net.yudichev.jiotty.energy.octopus.PriceForecastServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAgreementWindowNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeTariffSourcesNode;
import net.yudichev.jiotty.energy.octopus.provider.ForecastNode.Forecast;
import net.yudichev.jiotty.energy.octopus.provider.ProviderTestFixtures.FakeForecastPriceService;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static net.yudichev.jiotty.energy.octopus.provider.ProviderTestFixtures.createAgreement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ForecastNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final String AGILE_A = "E-1R-AGILE-23-12-06-A";
    private static final String AGILE_B = "E-1R-AGILE-23-12-06-B";
    private static final String FLEXIBLE_A = "E-1R-VAR-22-11-01-A";
    private static final Prices FORECAST = createFlatPrices(START, 96, 15.0);

    private final TestGraph graph = TestGraph.startingAt(START);
    private final LatestForecasts latestForecasts = new LatestForecasts();
    private final FakeForecastPriceService forecastA = new FakeForecastPriceService();
    private final FakeForecastPriceService forecastB = new FakeForecastPriceService();
    @Mock
    private PriceForecastServiceRegistry forecastRegistry;
    @Mock
    private OctopusTariffPriceService tariffService;
    private FakeAgreementWindowNode agreementWindowNode;
    private FakeTariffSourcesNode tariffSourcesNode;
    private ForecastNodeImpl node;

    @BeforeEach
    void setUp() {
        lenient().when(forecastRegistry.forRegion('A')).thenReturn(forecastA);
        lenient().when(forecastRegistry.forRegion('B')).thenReturn(forecastB);
        agreementWindowNode = new FakeAgreementWindowNode(graph.runner());
        agreementWindowNode.registerInGraph();
        tariffSourcesNode = new FakeTariffSourcesNode(graph.runner());
        tariffSourcesNode.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void untilTheAgreementsAndResolutionsAreKnown_theForecastIsUnknown() {
        registerNode();

        assertThat(node.forecast()).isNull();
    }

    @Test
    void noDynamicTariff_hasNoForecast_andSubscribesToNone() {
        setTariffs(Map.of(FLEXIBLE_A, TariffPricing.PUBLISHED), FLEXIBLE_A);
        registerNode();

        assertThat(node.forecast()).isSameAs(Forecast.None.INSTANCE);
        assertThat(forecastA.subscribers()).isEmpty();
    }

    @Test
    void forecastOfTheFirstDynamicTariffsRegion_isPublished_andRecorded() {
        setTariffs(Map.of(FLEXIBLE_A, TariffPricing.PUBLISHED, AGILE_B, TariffPricing.DYNAMIC), FLEXIBLE_A, AGILE_B);
        registerNode();
        assertThat(node.forecast()).describedAs("before a forecast arrives").isSameAs(Forecast.None.INSTANCE);

        forecastB.publish(FORECAST);
        graph.clock().tick();

        assertThat(node.forecast()).isEqualTo(new Forecast.Available(FORECAST));
        assertThat(latestForecasts.forRegion('B')).hasValue(FORECAST);
    }

    @Test
    void recordedForecast_isPublishedBeforeTheServiceDeliversOne() {
        latestForecasts.record('A', FORECAST);
        setTariffs(Map.of(AGILE_A, TariffPricing.DYNAMIC), AGILE_A);
        registerNode();

        assertThat(node.forecast()).isEqualTo(new Forecast.Available(FORECAST));
    }

    @Test
    void regionChange_movesTheSubscription_andDropsTheOldRegionsDeliveries() {
        setTariffs(Map.of(AGILE_A, TariffPricing.DYNAMIC), AGILE_A);
        registerNode();
        Consumer<Prices> oldSubscriber = forecastA.subscribers().getFirst();

        setTariffs(Map.of(AGILE_B, TariffPricing.DYNAMIC), AGILE_B);
        graph.runWave();
        oldSubscriber.accept(FORECAST);
        graph.clock().tick();

        assertThat(forecastA.subscribers()).isEmpty();
        assertThat(forecastB.subscribers()).hasSize(1);
        assertThat(node.forecast()).isSameAs(Forecast.None.INSTANCE);
    }

    private void setTariffs(Map<String, TariffPricing> pricingsByTariffCode, String... tariffCodesInOrder) {
        List<Tariff> agreements = new ArrayList<>();
        for (String tariffCode : tariffCodesInOrder) {
            agreements.add(createAgreement(tariffCode, START, null));
        }
        agreementWindowNode.set(agreements);
        Map<String, TariffResolution> resolutionsByTariffCode = new HashMap<>();
        pricingsByTariffCode.forEach((tariffCode, pricing) -> {
            var source = new TariffPriceSource.Priced(tariffService, pricing, false);
            resolutionsByTariffCode.put(tariffCode, new Resolved(source));
        });
        tariffSourcesNode.set(resolutionsByTariffCode);
    }

    private void registerNode() {
        node = new ForecastNodeImpl(forecastRegistry, graph.runner(), agreementWindowNode, tariffSourcesNode, latestForecasts);
        node.registerInGraph();
        graph.runWave();
    }
}
