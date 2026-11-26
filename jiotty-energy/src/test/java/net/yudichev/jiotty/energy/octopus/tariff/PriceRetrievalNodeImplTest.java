package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;

class PriceRetrievalNodeImplTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:10:00Z");
    private static final Instant DAILY_RETRIEVAL = Instant.parse("2026-11-21T18:00:00Z");
    private static final Duration ATTEMPT_DELAY = Duration.ofMinutes(5);
    private static final Instant ATTEMPT_FROM = NOW.minus(Duration.ofHours(1));
    private static final Prices PRICES = createFlatPrices(Instant.parse("2026-11-21T10:00:00Z"), 48, 20.0);

    private final TestGraph graph = TestGraph.startingAt(NOW);
    private final FakeTariffRetrieval retrieval = new FakeTariffRetrieval();
    private final FetchHistory fetchHistory = new FetchHistory();
    private FakeDailyRetrievalNode dailyRetrievalNode;
    private PriceRetrievalNodeImpl node;

    @BeforeEach
    void setUp() {
        dailyRetrievalNode = new FakeDailyRetrievalNode(graph.runner());
        dailyRetrievalNode.registerInGraph();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void untilTheDailyRetrievalIsKnown_nothingIsRetrieved() {
        registerNode();

        assertThat(retrieval.froms).isEmpty();
        assertThat(node.outcome()).isNull();
    }

    @Test
    void firstWave_retrievesFromNow_andPublishesTheOutcomeAndItsPlan() {
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();

        assertThat(retrieval.froms).containsExactly(NOW);
        assertThat(node.outcome()).isEqualTo(new Retrieved(PRICES, NOW));
        assertThat(node.plan()).isEqualTo(RetrievalPlan.builder().setNextRefreshTime(DAILY_RETRIEVAL).build());
    }

    @Test
    void plannedAttempt_retrievesFromItsInstant_whenItFallsDue() {
        retrieval.plannedAttempt = true;
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();

        graph.clock().advanceTimeAndTick(ATTEMPT_DELAY.minusNanos(1));
        assertThat(retrieval.froms).containsExactly(NOW);

        graph.clock().advanceTimeAndTick(Duration.ofNanos(1));
        assertThat(retrieval.froms).containsExactly(NOW, ATTEMPT_FROM);
    }

    @Test
    void dailyRetrieval_retrievesFromNow_andSupersedesTheArmedAttempt() {
        retrieval.plannedAttempt = true;
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();
        retrieval.plannedAttempt = false;

        graph.clock().advanceTimeAndTick(ATTEMPT_DELAY.dividedBy(2));
        dailyRetrievalNode.set(DAILY_RETRIEVAL.plus(Duration.ofDays(1)));
        graph.runWave();
        graph.clock().advanceTimeAndTick(ATTEMPT_DELAY);

        assertThat(retrieval.froms).containsExactly(NOW, NOW.plus(ATTEMPT_DELAY.dividedBy(2)));
    }

    @Test
    void failedRetrieval_isPublishedAsFailed() {
        retrieval.fetchResult = failure("octopus is down");
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();

        assertThat(node.outcome()).isInstanceOfSatisfying(Failed.class, failed -> assertThat(failed.cause()).hasMessage("octopus is down"));
    }

    @Test
    void recentRetrievalInTheHistory_delaysTheFirstRetrievalToTheRetryDelayAfterIt() {
        fetchHistory.recordStart(NOW.minus(FAILURE_RETRY_DELAY.dividedBy(3)));
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();
        assertThat(retrieval.froms).isEmpty();

        graph.clock().setTimeAndTick(NOW.minus(FAILURE_RETRY_DELAY.dividedBy(3)).plus(FAILURE_RETRY_DELAY));
        assertThat(retrieval.froms).containsExactly(graph.clock().currentInstant());
    }

    @Test
    void retrievalEndingAfterTheGraphCloses_isDropped() {
        var heldResult = new CompletableFuture<Prices>();
        retrieval.fetchResult = heldResult;
        dailyRetrievalNode.set(DAILY_RETRIEVAL);
        registerNode();

        graph.graph().close();
        heldResult.complete(PRICES);
        graph.clock().tick();

        assertThat(node.outcome()).isNull();
    }

    private void registerNode() {
        node = new PriceRetrievalNodeImpl(graph.runner(), retrieval, dailyRetrievalNode, fetchHistory);
        node.registerInGraph();
        graph.runWave();
    }

    /// A [TariffRetrieval] whose fetches return the result that the test last set, and which records the `from` of each fetch. Each plan has its next
    /// refresh time at [#DAILY_RETRIEVAL] and, while the test has switched planned attempts on, an attempt [#ATTEMPT_DELAY] later that fetches from
    /// [#ATTEMPT_FROM].
    private static final class FakeTariffRetrieval implements TariffRetrieval {
        private final List<Instant> froms = new ArrayList<>();
        private CompletableFuture<Prices> fetchResult = completedFuture(PRICES);
        private boolean plannedAttempt;

        @Override
        public LocalTime dailyRetrievalTime() {
            return LocalTime.of(18, 0);
        }

        @Override
        public CompletableFuture<Prices> fetch(Instant from) {
            froms.add(from);
            return fetchResult;
        }

        @Override
        public RetrievalPlan plan(RetrievalOutcome outcome, Instant now) {
            return RetrievalPlan.builder()
                                .setAttempt(plannedAttempt ? RetrievalAttempt.builder().setAt(now.plus(ATTEMPT_DELAY)).setFrom(ATTEMPT_FROM).build() : null)
                                .setNextRefreshTime(DAILY_RETRIEVAL)
                                .build();
        }

        @Override
        public boolean publishesFailureAtOnce(boolean pricesPublished) {
            return true;
        }
    }
}
