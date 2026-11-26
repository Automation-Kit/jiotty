package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.energy.octopus.TestGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.nextUkTimeOfDayAfter;
import static org.assertj.core.api.Assertions.assertThat;

class DailyRetrievalNodeImplTest {
    private static final LocalTime DAILY_RETRIEVAL_TIME = LocalTime.of(18, 0);
    private static final Instant NOW = Instant.parse("2026-11-21T10:10:00Z");
    private static final Instant TODAYS_RETRIEVAL = nextUkTimeOfDayAfter(NOW, DAILY_RETRIEVAL_TIME);

    private final TestGraph graph = TestGraph.startingAt(NOW);
    private DailyRetrievalNodeImpl node;

    @BeforeEach
    void setUp() {
        node = new DailyRetrievalNodeImpl(graph.runner(), DAILY_RETRIEVAL_TIME);
        node.registerInGraph();
        graph.runWave();
    }

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void firstWave_publishesTheNextDailyRetrieval() {
        assertThat(node.nextDailyRetrieval()).isEqualTo(TODAYS_RETRIEVAL);
    }

    @Test
    void justBeforeTheDailyRetrieval_theValueIsUnchanged() {
        graph.clock().setTimeAndTick(TODAYS_RETRIEVAL.minusNanos(1));

        assertThat(node.nextDailyRetrieval()).isEqualTo(TODAYS_RETRIEVAL);
    }

    @Test
    void atEachDailyRetrieval_theNextOneIsPublished() {
        graph.clock().setTimeAndTick(TODAYS_RETRIEVAL);
        assertThat(node.nextDailyRetrieval()).isEqualTo(TODAYS_RETRIEVAL.plus(Duration.ofDays(1)));

        graph.clock().setTimeAndTick(TODAYS_RETRIEVAL.plus(Duration.ofDays(1)));
        assertThat(node.nextDailyRetrieval()).isEqualTo(TODAYS_RETRIEVAL.plus(Duration.ofDays(2)));
    }

    @Test
    void closedNode_isNotWokenAtTheDailyRetrieval() {
        graph.graph().close();

        graph.clock().setTimeAndTick(TODAYS_RETRIEVAL);

        assertThat(node.nextDailyRetrieval()).isEqualTo(TODAYS_RETRIEVAL);
    }
}
