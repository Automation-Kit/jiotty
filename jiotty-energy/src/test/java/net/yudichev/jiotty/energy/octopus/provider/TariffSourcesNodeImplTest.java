package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeAgreementWindowNode;
import net.yudichev.jiotty.energy.octopus.provider.ResolutionHistory.FailedResolution;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Failed;
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
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.provider.ProviderTestFixtures.createAgreement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TariffSourcesNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final String TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final String TARIFF_B = "E-1R-VAR-22-11-01-A";
    private static final RuntimeException FAILURE = new RuntimeException("product details are down");

    private final TestGraph graph = TestGraph.startingAt(START);
    private final ResolutionHistory resolutionHistory = new ResolutionHistory();
    @Mock
    private OctopusTariffPriceServiceRegistry registry;
    private FakeAgreementWindowNode agreementWindowNode;
    private TariffSourcesNodeImpl node;

    @BeforeEach
    void setUp() {
        agreementWindowNode = new FakeAgreementWindowNode(graph.runner());
        agreementWindowNode.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void untilTheWindowIsKnown_theResolutionsAreUnknown() {
        registerNode();

        assertThat(node.resolutionsByTariffCode()).isNull();
    }

    @Test
    void eachTariffInTheWindow_isResolvedOnce() {
        when(registry.forTariff(TARIFF_A)).thenReturn(completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null), createAgreement(TARIFF_A, START, null)));
        registerNode();
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null)));
        graph.runWave();

        verify(registry, times(1)).forTariff(TARIFF_A);
        assertThat(node.resolutionsByTariffCode()).containsOnly(entry(TARIFF_A, new Resolved(TariffPriceSource.Unsupported.INSTANCE)));
    }

    @Test
    void resolutionStillRunning_isPublishedAsResolving() {
        when(registry.forTariff(TARIFF_A)).thenReturn(new CompletableFuture<>());
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null)));
        registerNode();

        assertThat(node.resolutionsByTariffCode()).containsEntry(TARIFF_A, Resolving.INSTANCE);
    }

    @Test
    void failedResolutions_areRetriedTogetherAfterTheRetryDelay() {
        when(registry.forTariff(TARIFF_A)).thenReturn(failedFuture(FAILURE), completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        when(registry.forTariff(TARIFF_B)).thenReturn(failedFuture(FAILURE), completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null), createAgreement(TARIFF_B, START, null)));
        registerNode();
        assertThat(node.resolutionsByTariffCode()).containsEntry(TARIFF_A, new Failed(FAILURE)).containsEntry(TARIFF_B, new Failed(FAILURE));

        graph.clock().advanceTimeAndTick(FAILURE_RETRY_DELAY);

        assertThat(node.resolutionsByTariffCode()).containsEntry(TARIFF_A, new Resolved(TariffPriceSource.Unsupported.INSTANCE))
                                                  .containsEntry(TARIFF_B, new Resolved(TariffPriceSource.Unsupported.INSTANCE));
    }

    @Test
    void failureInTheHistory_isPublished_andResolvedAgainAtTheRetryDelayAfterIt() {
        Instant failedAt = START.minus(FAILURE_RETRY_DELAY.dividedBy(3));
        Instant retryTime = failedAt.plus(FAILURE_RETRY_DELAY);
        resolutionHistory.recordFailure(TARIFF_A, new FailedResolution(failedAt, FAILURE));
        when(registry.forTariff(TARIFF_A)).thenReturn(completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null)));
        registerNode();
        assertThat(node.resolutionsByTariffCode()).containsEntry(TARIFF_A, new Failed(FAILURE));

        graph.clock().setTimeAndTick(retryTime.minusNanos(1));
        verify(registry, never()).forTariff(TARIFF_A);

        graph.clock().setTimeAndTick(retryTime);

        assertThat(node.resolutionsByTariffCode()).containsEntry(TARIFF_A, new Resolved(TariffPriceSource.Unsupported.INSTANCE));
        assertThat(resolutionHistory.failedResolution(TARIFF_A)).isEmpty();
    }

    @Test
    void resolutionEndingAfterTheTariffLeftTheWindow_isIgnored() {
        var resolution = new CompletableFuture<TariffPriceSource>();
        when(registry.forTariff(TARIFF_A)).thenReturn(resolution);
        when(registry.forTariff(TARIFF_B)).thenReturn(completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        agreementWindowNode.set(List.of(createAgreement(TARIFF_A, START, null)));
        registerNode();
        agreementWindowNode.set(List.of(createAgreement(TARIFF_B, START, null)));
        graph.runWave();

        resolution.complete(TariffPriceSource.Unsupported.INSTANCE);
        graph.clock().tick();

        assertThat(node.resolutionsByTariffCode()).containsOnlyKeys(TARIFF_B);
    }

    private void registerNode() {
        node = new TariffSourcesNodeImpl(registry, graph.runner(), agreementWindowNode, resolutionHistory);
        node.registerInGraph();
        graph.runWave();
    }
}
