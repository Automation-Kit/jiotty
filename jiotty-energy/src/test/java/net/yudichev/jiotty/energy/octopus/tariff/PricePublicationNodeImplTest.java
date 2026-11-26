package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.BaseTestServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;

class PricePublicationNodeImplTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:10:00Z");
    private static final Instant DAILY_RETRIEVAL = Instant.parse("2026-11-21T18:00:00Z");
    private static final Prices PRICES = createFlatPrices(Instant.parse("2026-11-21T10:00:00Z"), 48, 20.0);
    private static final RuntimeException FAILURE = new RuntimeException("octopus is down");

    private final TestGraph graph = TestGraph.startingAt(NOW);
    private final FakeTariffRetrieval retrieval = new FakeTariffRetrieval();
    private FakeDailyRetrievalNode dailyRetrievalNode;
    private FakePriceRetrievalNode priceRetrievalNode;
    private PricePublicationNodeImpl node;

    @BeforeEach
    void setUp() {
        dailyRetrievalNode = new FakeDailyRetrievalNode(graph.runner());
        dailyRetrievalNode.registerInGraph();
        priceRetrievalNode = new FakePriceRetrievalNode(graph.runner());
        priceRetrievalNode.registerInGraph();
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void beforeAnyOutcome_publishesWhatSubscribersHold() {
        registerNode(Optional.of(PRICES));

        assertThat(node.publishedResult()).isEqualTo(PRICES);
    }

    @Test
    void retrievedPrices_arePublished() {
        registerNode(Optional.empty());

        deliver(new Retrieved(PRICES, NOW));

        assertThat(node.publishedResult()).isEqualTo(PRICES);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failure_isPublishedAtOnce_whenPublishesFailureAtOnceReturnsTrue(boolean pricesPublished) {
        retrieval.failuresAtOnce = true;
        registerNode(pricesPublished ? Optional.of(PRICES) : Optional.empty());
        retrieval.pricesPublishedAsked = null;

        deliver(new Failed(FAILURE, NOW));

        assertThat(retrieval.pricesPublishedAsked).isEqualTo(pricesPublished);
        assertThat(node.publishedResult()).isInstanceOfSatisfying(Failure.PriceRetrievalError.class, error -> assertThat(error.cause()).isSameAs(FAILURE));
    }

    @Test
    void deferredFailure_isPublishedAtTheNextDailyRetrieval() {
        registerNode(Optional.of(PRICES));
        deliver(new Failed(FAILURE, NOW));
        assertThat(node.publishedResult()).isEqualTo(PRICES);

        passTheDailyRetrieval();

        assertThat(node.publishedResult()).isInstanceOfSatisfying(Failure.PriceRetrievalError.class, error -> assertThat(error.cause()).isSameAs(FAILURE));
    }

    @Test
    void pricesAfterADeferredFailure_dropIt() {
        registerNode(Optional.of(PRICES));
        deliver(new Failed(FAILURE, NOW));
        Prices laterPrices = createFlatPrices(Instant.parse("2026-11-21T11:00:00Z"), 48, 21.0);
        deliver(new Retrieved(laterPrices, NOW));

        passTheDailyRetrieval();

        assertThat(node.publishedResult()).isEqualTo(laterPrices);
    }

    /// @param publishedResult the result that subscribers hold when the node is created; empty when they hold none yet
    private void registerNode(Optional<PriceResult> publishedResult) {
        node = new PricePublicationNodeImpl(graph.runner(), retrieval, dailyRetrievalNode, priceRetrievalNode, publishedResult);
        node.registerInGraph();
        runWave();
    }

    private void deliver(RetrievalOutcome outcome) {
        priceRetrievalNode.set(outcome);
        runWave();
    }

    private void passTheDailyRetrieval() {
        dailyRetrievalNode.set(DAILY_RETRIEVAL.plus(Duration.ofDays(1)));
        runWave();
    }

    private void runWave() {
        graph.runWave();
    }

    private static final class FakePriceRetrievalNode extends BaseTestServerNode implements PriceRetrievalNode {
        private @Nullable RetrievalOutcome outcome;

        FakePriceRetrievalNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable RetrievalOutcome outcome() {
            return outcome;
        }

        @Override
        public @Nullable RetrievalPlan plan() {
            return null;
        }

        void set(RetrievalOutcome value) {
            outcome = value;
            triggerInNextWave();
        }
    }

    /// A [TariffRetrieval] that defers failures unless the test has set it to publish them at once, and records the argument of the latest
    /// [#publishesFailureAtOnce] call.
    private static final class FakeTariffRetrieval implements TariffRetrieval {
        private boolean failuresAtOnce;
        private @Nullable Boolean pricesPublishedAsked;

        @Override
        public LocalTime dailyRetrievalTime() {
            return LocalTime.of(18, 0);
        }

        @Override
        public CompletableFuture<Prices> fetch(Instant from) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RetrievalPlan plan(RetrievalOutcome outcome, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean publishesFailureAtOnce(boolean pricesPublished) {
            pricesPublishedAsked = pricesPublished;
            return failuresAtOnce;
        }
    }
}
