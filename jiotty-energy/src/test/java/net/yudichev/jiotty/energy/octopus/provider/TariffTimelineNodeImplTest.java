package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.TariffPeriod;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceService;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAgreementWindowNode;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeTariffSourcesNode;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolving;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TariffTimelineNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant SWITCH = Instant.parse("2024-01-03T00:00:00Z");
    private static final Tariff IOG = Tariff.builder().setTariffCode("E-1R-IOG-SMB-VAR-26-11-04-A").setValidFrom(START).setValidTo(SWITCH).build();
    private static final Tariff TRACKER = Tariff.builder().setTariffCode("E-1R-SILVER-24-12-31-A").setValidFrom(SWITCH).build();
    private static final TariffTimeline IOG_TIMELINE = new TariffTimeline(List.of(
            TariffPeriod.builder().setFrom(START).setTo(SWITCH).setPricing(TariffPricing.PUBLISHED).setSupplierManagedCharging(true).build()));

    private final TestGraph graph = TestGraph.startingAt(START);
    @Mock
    private OctopusTariffPriceService iogService;
    private FakeAgreementWindowNode agreementWindowNode;
    private FakeTariffSourcesNode tariffSourcesNode;
    private TariffTimelineNodeImpl node;

    @BeforeEach
    void setUp() {
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
    void untilEveryTariffIsResolved_theTimelineIsUnknown() {
        registerNode(Optional.empty());
        agreementWindowNode.set(List.of(IOG, TRACKER));
        graph.runWave();
        assertThat(node.timeline()).isNull();

        tariffSourcesNode.set(Map.of(IOG.tariffCode(), createPricedResolution(), TRACKER.tariffCode(), Resolving.INSTANCE));
        graph.runWave();
        assertThat(node.timeline()).isNull();
    }

    @Test
    void everyTariffResolved_makesOnePeriodPerAgreement_pricedAsItsTariff() {
        registerNode(Optional.empty());
        agreementWindowNode.set(List.of(IOG, TRACKER));
        tariffSourcesNode.set(Map.of(IOG.tariffCode(),
                                     createPricedResolution(),
                                     TRACKER.tariffCode(),
                                     new Resolved(TariffPriceSource.Unsupported.INSTANCE)));
        graph.runWave();

        assertThat(node.timeline()).isEqualTo(new TariffTimeline(List.of(
                TariffPeriod.builder().setFrom(START).setTo(SWITCH).setPricing(TariffPricing.PUBLISHED).setSupplierManagedCharging(true).build(),
                TariffPeriod.builder().setFrom(SWITCH).setPricing(TariffPricing.UNSUPPORTED).setSupplierManagedCharging(false).build())));
    }

    @Test
    void agreementWithAnUnresolvedTariffEnteringTheWindow_keepsTheLatestTimeline() {
        registerNode(Optional.empty());
        agreementWindowNode.set(List.of(IOG));
        tariffSourcesNode.set(Map.of(IOG.tariffCode(), createPricedResolution()));
        graph.runWave();
        assertThat(node.timeline()).isEqualTo(IOG_TIMELINE);

        agreementWindowNode.set(List.of(IOG, TRACKER));
        tariffSourcesNode.set(Map.of(IOG.tariffCode(), createPricedResolution(), TRACKER.tariffCode(), Resolving.INSTANCE));
        graph.runWave();

        assertThat(node.timeline()).isEqualTo(IOG_TIMELINE);
    }

    @Test
    void startsFromTheTimelineThatSubscribersHold_untilEveryTariffIsResolved() {
        registerNode(Optional.of(IOG_TIMELINE));
        agreementWindowNode.set(List.of(IOG, TRACKER));
        tariffSourcesNode.set(Map.of(IOG.tariffCode(), createPricedResolution(), TRACKER.tariffCode(), Resolving.INSTANCE));
        graph.runWave();

        assertThat(node.timeline()).isEqualTo(IOG_TIMELINE);
    }

    private void registerNode(Optional<TariffTimeline> timeline) {
        node = new TariffTimelineNodeImpl(graph.runner(), agreementWindowNode, tariffSourcesNode, timeline);
        node.registerInGraph();
    }

    private Resolved createPricedResolution() {
        return new Resolved(new TariffPriceSource.Priced(iogService, TariffPricing.PUBLISHED, true));
    }
}
