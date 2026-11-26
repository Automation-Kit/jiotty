package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAccountNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAgreementWindowNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeForecastNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeTariffPricesNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeTariffTimelineNode;
import net.yudichev.jiotty.energy.octopus.provider.ForecastNode.Forecast;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static net.yudichev.jiotty.energy.octopus.provider.ProviderTestFixtures.createAgreement;
import static org.assertj.core.api.Assertions.assertThat;

class JoinedPricesNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final Duration FORWARD_WINDOW = Duration.ofDays(1);
    private static final Instant SWITCH = START.plus(Duration.ofHours(6));
    private static final String TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final String TARIFF_B = "E-1R-VAR-22-11-01-A";
    private static final Prices PRICES_A = createFlatPrices(START, 96, 20.0);
    private static final Instant REFRESH_TIME = Instant.parse("2024-01-01T16:00:00Z");

    private final TestGraph graph = TestGraph.startingAt(START);
    private FakeAccountNode accountNode;
    private FakeAgreementWindowNode agreementWindowNode;
    private FakeTariffPricesNode tariffPricesNode;
    private FakeForecastNode forecastNode;
    private FakeTariffTimelineNode tariffTimelineNode;
    private JoinedPricesNodeImpl node;

    @BeforeEach
    void setUp() {
        accountNode = new FakeAccountNode(graph.runner());
        agreementWindowNode = new FakeAgreementWindowNode(graph.runner());
        tariffPricesNode = new FakeTariffPricesNode(graph.runner());
        forecastNode = new FakeForecastNode(graph.runner());
        tariffTimelineNode = new FakeTariffTimelineNode(graph.runner());
        accountNode.registerInGraph();
        agreementWindowNode.registerInGraph();
        tariffPricesNode.registerInGraph();
        forecastNode.registerInGraph();
        tariffTimelineNode.registerInGraph();
        node = new JoinedPricesNodeImpl(FORWARD_WINDOW, graph.runner(), accountNode, agreementWindowNode, tariffPricesNode, forecastNode, tariffTimelineNode);
        node.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void beforeTheAccountIsFetched_theResultIsUnknown() {
        graph.runWave();

        assertThat(node.result()).isNull();
        assertThat(node.nextRefreshTime()).isNull();
    }

    @Test
    void unavailableAccount_isARetrievalError() {
        RuntimeException failure = new RuntimeException("octopus is down");
        accountNode.set(new Unavailable(failure));
        graph.runWave();

        assertThat(node.result()).isEqualTo(new Failure.PriceRetrievalError(failure));
    }

    @Test
    void pricesOfTheAgreementInForce_arePublishedOnceTheTimelineIsKnown_withTheirRefreshTime() {
        loadAgreementsWithPricesA(List.of(createAgreement(TARIFF_A, START.minus(Duration.ofDays(1)), null)), Map.of());
        graph.runWave();
        assertThat(node.result()).describedAs("before the timeline").isNull();
        assertThat(node.nextRefreshTime()).isEqualTo(REFRESH_TIME);

        tariffTimelineNode.set(new TariffTimeline(List.of()));
        graph.runWave();

        assertThat(node.result()).isInstanceOfSatisfying(Prices.class, prices -> assertThat(prices.profileStart()).isEqualTo(START));
    }

    @Test
    void failureOfTheAgreementInForce_isPublishedWithoutTheTimeline() {
        RuntimeException failure = new RuntimeException("product details are down");
        accountNode.set(new Loaded(OctopusAccountData.builder().build()));
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START.minus(Duration.ofDays(1)), null)));
        tariffPricesNode.set(Map.of(TARIFF_A, new TariffPriceState.ResolutionFailed(failure)));
        forecastNode.set(Forecast.None.INSTANCE);
        graph.runWave();

        assertThat(node.result()).isInstanceOfSatisfying(Failure.PriceRetrievalError.class, error -> assertThat(error.cause()).isSameAs(failure));
    }

    @Test
    void atASwitchInsideTheWindow_theAgreementInForceChanges() {
        loadAgreementsWithPricesA(List.of(createAgreement(TARIFF_A, START.minus(Duration.ofDays(1)), SWITCH), createAgreement(TARIFF_B, SWITCH, null)),
                                  Map.of(TARIFF_B, TariffPriceState.Resolving.INSTANCE));
        tariffTimelineNode.set(new TariffTimeline(List.of()));
        graph.runWave();
        assertThat(node.result()).isInstanceOf(Prices.class);

        graph.clock().setTimeAndTick(SWITCH);

        assertThat(node.result()).describedAs("the agreement in force is still resolving").isNull();
    }

    /// Loads an account with `agreements` in the forward window, prices [#TARIFF_A] at [#PRICES_A], and gives every other tariff its state from
    /// `otherPriceStates`.
    private void loadAgreementsWithPricesA(List<Tariff> agreements, Map<String, TariffPriceState> otherPriceStates) {
        accountNode.set(new Loaded(OctopusAccountData.builder().build()));
        agreementWindowNode.set(agreements);
        var priceStates = new HashMap<>(otherPriceStates);
        priceStates.put(TARIFF_A, new TariffPriceState.Priced(Optional.of(PRICES_A), Optional.of(REFRESH_TIME), true));
        tariffPricesNode.set(priceStates);
        forecastNode.set(Forecast.None.INSTANCE);
    }
}
