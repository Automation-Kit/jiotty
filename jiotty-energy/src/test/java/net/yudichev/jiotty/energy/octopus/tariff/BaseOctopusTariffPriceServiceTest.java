package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.nextUkTimeOfDayAfter;
import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static net.yudichev.jiotty.energy.octopus.tariff.BaseOctopusTariffPriceService.GRAPH_TASK_NAME_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

class BaseOctopusTariffPriceServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-21T10:10:00Z");
    private static final LocalTime DAILY_RETRIEVAL_TIME = LocalTime.of(18, 0);
    private static final Instant DAILY_RETRIEVAL = nextUkTimeOfDayAfter(NOW, DAILY_RETRIEVAL_TIME);
    private static final Prices PRICES = createFlatPrices(OctopusPriceSlots.floorToSlot(NOW), 48, 20.0);
    private static final String TARIFF_CODE = "E-1R-TEST-A";
    /// Longer than the first delay before a panicked graph is rebuilt, and shorter than [OctopusPriceSlots#FAILURE_RETRY_DELAY].
    private static final Duration REBUILD_WAIT = Duration.ofSeconds(1);

    private final ListenerBackedTaskExceptionHandlerRegistry taskFailureRegistry = new ListenerBackedTaskExceptionHandlerRegistry();
    private final List<String> reportedTasks = new ArrayList<>();
    private final List<PriceResult> results = new ArrayList<>();
    private ProgrammableClock clock;
    private TestTariffPriceService service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(NOW);
        taskFailureRegistry.addExceptionHandler((taskDescription, _) -> reportedTasks.add(taskDescription));
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("tariff");
        service = new TestTariffPriceService(executor);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
            clock.tick();
        }
    }

    @Test
    void start_retrievesFromNow_andPublishesThePricesAndTheNextRefreshTime() {
        List<Instant> refreshTimes = new ArrayList<>();
        service.start();
        service.subscribeToPrices(results::add);
        service.subscribeToNextRefreshTime(refreshTimes::add);
        clock.tick();

        assertThat(service.fetchFroms).containsExactly(NOW);
        assertThat(results).containsExactly(PRICES);
        assertThat(refreshTimes).containsExactly(DAILY_RETRIEVAL);
    }

    @Test
    void bugInThePlan_isReported_andPublishesNothingFromTheFailedWave() {
        startWithThePricesPublished();
        service.planBug = new IllegalStateException("plan bug");
        service.fetchResult = completedFuture(createFlatPrices(DAILY_RETRIEVAL, 48, 30.0));

        clock.setTimeAndTick(DAILY_RETRIEVAL);

        assertThat(reportedTasks).containsExactly(GRAPH_TASK_NAME_PREFIX + TARIFF_CODE);
        assertThat(results).containsExactly(PRICES);
    }

    @Test
    void rebuildAfterAPanic_retrievesAtTheRetryDelay() {
        startWithThePricesPublished();
        service.planBug = new IllegalStateException("plan bug");
        clock.setTimeAndTick(DAILY_RETRIEVAL);
        service.planBug = null;

        clock.advanceTimeAndTick(REBUILD_WAIT);
        assertThat(service.fetchFroms).describedAs("no retrieval by the rebuilt graph yet").containsExactly(NOW, DAILY_RETRIEVAL);

        clock.setTimeAndTick(DAILY_RETRIEVAL.plus(FAILURE_RETRY_DELAY).minusNanos(1));
        assertThat(service.fetchFroms).describedAs("no retrieval just before the retry delay").containsExactly(NOW, DAILY_RETRIEVAL);

        clock.setTimeAndTick(DAILY_RETRIEVAL.plus(FAILURE_RETRY_DELAY));
        assertThat(service.fetchFroms).containsExactly(NOW, DAILY_RETRIEVAL, DAILY_RETRIEVAL.plus(FAILURE_RETRY_DELAY));
    }

    @Test
    void failureAfterARebuild_keepsThePublishedPrices_whenPricesDeferFailures() {
        startWithThePricesPublished();
        service.planBug = new IllegalStateException("plan bug");
        clock.setTimeAndTick(DAILY_RETRIEVAL);
        service.planBug = null;
        service.fetchResult = failure("octopus is down");

        clock.setTimeAndTick(DAILY_RETRIEVAL.plus(FAILURE_RETRY_DELAY));

        assertThat(service.fetchFroms).hasSize(3);
        assertThat(results).containsExactly(PRICES);
    }

    @Test
    void failureWithNoPricesPublished_isPublishedAtOnce() {
        service.fetchResult = failure("octopus is down");

        service.start();
        service.subscribeToPrices(results::add);
        clock.tick();

        assertThat(results).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
    }

    private void startWithThePricesPublished() {
        service.start();
        service.subscribeToPrices(results::add);
        clock.tick();
        assertThat(results).containsExactly(PRICES);
    }

    /// A price service whose:
    /// - [#fetch] returns the result that the test last set, for any `from`, and records each `from`
    /// - [#publishesFailureAtOnce] returns `true` only while no prices are published
    private final class TestTariffPriceService extends BaseOctopusTariffPriceService {
        private final List<Instant> fetchFroms = new ArrayList<>();
        private CompletableFuture<Prices> fetchResult = completedFuture(PRICES);
        /// Thrown by [#plan] while not `null`.
        private @Nullable RuntimeException planBug;

        TestTariffPriceService(SchedulingExecutor executor) {
            checkNotNull(executor);
            super(() -> executor, clock, () -> 0.5, taskFailureRegistry, TARIFF_CODE);
        }

        @Override
        public LocalTime dailyRetrievalTime() {
            return DAILY_RETRIEVAL_TIME;
        }

        @Override
        public CompletableFuture<Prices> fetch(Instant from) {
            fetchFroms.add(from);
            return fetchResult;
        }

        @Override
        public RetrievalPlan plan(RetrievalOutcome outcome, Instant now) {
            if (planBug != null) {
                throw planBug;
            }
            return switch (outcome) {
                case Retrieved _ -> RetrievalPlan.builder().setNextRefreshTime(nextUkTimeOfDayAfter(now, DAILY_RETRIEVAL_TIME)).build();
                case Failed _ -> RetrievalPlan.builder()
                                              .setAttempt(RetrievalAttempt.builder().setAt(now.plus(FAILURE_RETRY_DELAY)).build())
                                              .setNextRefreshTime(now.plus(FAILURE_RETRY_DELAY))
                                              .build();
            };
        }

        @Override
        public boolean publishesFailureAtOnce(boolean pricesPublished) {
            return !pricesPublished;
        }
    }
}
