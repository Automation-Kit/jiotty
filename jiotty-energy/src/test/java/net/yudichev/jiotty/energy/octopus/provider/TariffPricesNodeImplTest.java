package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceService;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import net.yudichev.jiotty.energy.octopus.provider.FakeProviderNodes.FakeTariffSourcesNode;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Failed;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolving;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;

class TariffPricesNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final String TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final Prices PRICES = createFlatPrices(START, 48, 20.0);
    private static final Instant REFRESH_TIME = Instant.parse("2024-01-01T16:00:00Z");

    private final TestGraph graph = TestGraph.startingAt(START);
    private final FakeTariffPriceService service = new FakeTariffPriceService();
    private FakeTariffSourcesNode tariffSourcesNode;
    private TariffPricesNodeImpl node;

    @BeforeEach
    void setUp() {
        tariffSourcesNode = new FakeTariffSourcesNode(graph.runner());
        tariffSourcesNode.registerInGraph();
        node = new TariffPricesNodeImpl(graph.runner(), tariffSourcesNode);
        node.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void untilTheResolutionsAreKnown_thePricesAreUnknown() {
        graph.runWave();

        assertThat(node.priceStatesByTariffCode()).isNull();
    }

    @Test
    void unpricedResolutions_mapToTheirPriceStates() {
        RuntimeException failure = new RuntimeException("product details are down");
        tariffSourcesNode.set(Map.of("RESOLVING", Resolving.INSTANCE,
                                     "FAILED", new Failed(failure),
                                     "UNSUPPORTED", new Resolved(TariffPriceSource.Unsupported.INSTANCE)));
        graph.runWave();

        assertThat(node.priceStatesByTariffCode()).containsOnly(Map.entry("RESOLVING", TariffPriceState.Resolving.INSTANCE),
                                                                Map.entry("FAILED", new TariffPriceState.ResolutionFailed(failure)),
                                                                Map.entry("UNSUPPORTED", TariffPriceState.Unsupported.INSTANCE));
    }

    @Test
    void pricedTariff_publishesWhatItsServiceDelivers() {
        tariffSourcesNode.set(Map.of(TARIFF_A, createPricedResolution()));
        graph.runWave();
        assertThat(node.priceStatesByTariffCode()).containsEntry(TARIFF_A, new TariffPriceState.Priced(Optional.empty(), Optional.empty(), true));

        service.publish(PRICES, REFRESH_TIME);
        graph.clock().tick();

        assertThat(node.priceStatesByTariffCode()).containsEntry(TARIFF_A, new TariffPriceState.Priced(Optional.of(PRICES), Optional.of(REFRESH_TIME), true));
    }

    @Test
    void tariffLeavingTheWindow_isUnsubscribed_andItsDeliveriesDropped() {
        tariffSourcesNode.set(Map.of(TARIFF_A, createPricedResolution()));
        graph.runWave();
        Consumer<PriceResult> subscriber = service.priceSubscribers.getFirst();

        tariffSourcesNode.set(Map.of());
        graph.runWave();
        subscriber.accept(PRICES);
        graph.clock().tick();

        assertThat(service.priceSubscribers).isEmpty();
        assertThat(node.priceStatesByTariffCode()).isEmpty();
    }

    private Resolved createPricedResolution() {
        return new Resolved(new TariffPriceSource.Priced(service, TariffPricing.DYNAMIC, false));
    }

    /// A fake [OctopusTariffPriceService] that records its subscribers and, when the test calls [#publish(PriceResult, Instant)], delivers the prices and
    /// the refresh time to them on the calling thread.
    private static final class FakeTariffPriceService implements OctopusTariffPriceService {
        private final List<Consumer<PriceResult>> priceSubscribers = new ArrayList<>();
        private final List<Consumer<Instant>> refreshTimeSubscribers = new ArrayList<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
            priceSubscribers.add(consumer);
            return () -> priceSubscribers.remove(consumer);
        }

        @Override
        public Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
            refreshTimeSubscribers.add(consumer);
            return () -> refreshTimeSubscribers.remove(consumer);
        }

        /// @param result      the prices to deliver to the price subscribers
        /// @param refreshTime the next refresh time to deliver to the refresh-time subscribers; `null` to deliver none
        void publish(PriceResult result, @Nullable Instant refreshTime) {
            priceSubscribers.forEach(subscriber -> subscriber.accept(result));
            if (refreshTime != null) {
                refreshTimeSubscribers.forEach(subscriber -> subscriber.accept(refreshTime));
            }
        }
    }
}
