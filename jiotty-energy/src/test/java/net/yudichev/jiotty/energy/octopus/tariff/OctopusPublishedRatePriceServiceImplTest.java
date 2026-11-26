package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.RateSchedule;
import net.yudichev.jiotty.energy.octopus.RateSchedule.RateSegment;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.UK_ZONE;
import static net.yudichev.jiotty.energy.octopus.OctopusStreams.DIRECT_DEBIT;
import static net.yudichev.jiotty.energy.octopus.TestPrices.FORWARD_WINDOW;
import static net.yudichev.jiotty.energy.octopus.TestRates.createRate;
import static net.yudichev.jiotty.energy.octopus.tariff.BaseOctopusTariffPriceService.GRAPH_TASK_NAME_PREFIX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusPublishedRatePriceServiceImplTest {
    private static final String PRODUCT = "VAR-22-11-01";
    private static final String TARIFF = "E-1R-VAR-22-11-01-A";
    private static final Instant NOW = Instant.parse("2026-11-21T10:10:00Z");
    private static final Instant SLOT = Instant.parse("2026-11-21T10:00:00Z");

    @Mock
    private OctopusRegionService regionService;
    private final ListenerBackedTaskExceptionHandlerRegistry taskFailureRegistry = new ListenerBackedTaskExceptionHandlerRegistry();
    private ProgrammableClock clock;
    private OctopusPublishedRatePriceServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(NOW);
        var executor = clock.createSingleThreadedSchedulingExecutor("rates");
        service = new OctopusPublishedRatePriceServiceImpl(() -> executor,
                                                           clock,
                                                           () -> 0.5,
                                                           FORWARD_WINDOW,
                                                           taskFailureRegistry,
                                                           regionService,
                                                           RateSchedule.STANDARD_UNIT_RATES,
                                                           PRODUCT,
                                                           TARIFF);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    void construction_refusesANonPositiveForwardWindow() {
        var executor = clock.createSingleThreadedSchedulingExecutor("refused-rates");

        assertThatThrownBy(() -> new OctopusPublishedRatePriceServiceImpl(() -> executor,
                                                                          clock,
                                                                          () -> 0.5,
                                                                          Duration.ZERO,
                                                                          taskFailureRegistry,
                                                                          regionService,
                                                                          RateSchedule.STANDARD_UNIT_RATES,
                                                                          PRODUCT,
                                                                          TARIFF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("forwardWindow must be positive: PT0S");
    }

    // ---- rasterise ----

    @Test
    void rasterise_flatRateWithAPriceCapChange_isAllActual() {
        Instant start = Instant.parse("2026-12-30T12:00:00Z");
        Instant change = Instant.parse("2027-01-01T00:00:00Z");
        Instant end = start.plus(FORWARD_WINDOW);

        Prices prices = OctopusPublishedRatePriceServiceImpl.rasterise(List.of(new RateSegment(Instant.parse("2026-09-30T23:00:00Z"), change, 26.5),
                                                                               new RateSegment(change, end, 30.0)),
                                                                       start,
                                                                       end);

        assertThat(prices.profileStart()).isEqualTo(start);
        assertThat(prices.profileEnd()).isEqualTo(end);
        assertThat(prices.profile().idxOfPredictedPriceStart()).isEqualTo(prices.profile().pricePerInterval().size());
        assertThat(prices.profile().pricePerInterval().get(prices.firstUnendedProfileIndex(change) - 1)).isEqualTo(26.5);
        assertThat(prices.profile().pricePerInterval().get(prices.firstUnendedProfileIndex(change))).isEqualTo(30.0);
    }

    /// Bands are published up to the end of 28 March 2026; the clocks go forward on the 29th, and the projected night band still starts at 00:30 UK time.
    @Test
    void rasterise_bandsPastTheLastPublishedDay_areRepeatedByUkClockTime_asActualPrices() {
        Instant start = Instant.parse("2026-03-28T00:00:00Z");
        Instant end = start.plus(Duration.ofDays(3));

        Prices prices = OctopusPublishedRatePriceServiceImpl.rasterise(createGoBands(LocalDate.of(2026, 3, 27), LocalDate.of(2026, 3, 28)), start, end);

        assertThat(prices.profile().idxOfPredictedPriceStart()).isEqualTo(prices.profile().pricePerInterval().size());
        assertThat(prices.profileEnd()).isEqualTo(end);
        assertThat(priceAt(prices, "2026-03-29T00:30:00Z")).describedAs("00:30 GMT, the night band").isEqualTo(7.0);
        assertThat(priceAt(prices, "2026-03-29T23:30:00Z")).describedAs("00:30 BST, the night band").isEqualTo(7.0);
        assertThat(priceAt(prices, "2026-03-30T03:30:00Z")).describedAs("04:30 BST, the day band").isEqualTo(30.0);
    }

    @Test
    void rasterise_endsWhereThereIsNoDayToProjectFrom() {
        Instant start = Instant.parse("2026-03-28T00:00:00Z");

        Prices prices = OctopusPublishedRatePriceServiceImpl.rasterise(List.of(new RateSegment(start, start.plus(Duration.ofHours(6)), 7.0)),
                                                                       start,
                                                                       start.plus(Duration.ofDays(3)));

        assertThat(prices.profileEnd()).isEqualTo(start.plus(Duration.ofHours(6)));
    }

    @Test
    void rasterise_aGapBetweenSegments_endsThePricesThere() {
        Instant start = Instant.parse("2026-03-28T00:00:00Z");
        Instant gapStart = start.plus(Duration.ofHours(1));
        Instant end = start.plus(Duration.ofDays(1));

        Prices prices = OctopusPublishedRatePriceServiceImpl.rasterise(List.of(new RateSegment(start, gapStart, 7.0),
                                                                               new RateSegment(gapStart.plus(Duration.ofHours(1)), end, 30.0)),
                                                                       start,
                                                                       end);

        assertThat(prices.profileEnd()).isEqualTo(gapStart);
    }

    @Test
    void rasterise_noRateCoveringTheStart_fails() {
        Instant start = Instant.parse("2026-03-28T00:00:00Z");

        assertThatThrownBy(() -> OctopusPublishedRatePriceServiceImpl.rasterise(List.of(), start, start.plus(Duration.ofDays(1))))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- retrieval ----

    @Test
    void retrievesOnStart_andExpectsTheNextDailyRetrieval() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554, DIRECT_DEBIT),
                                                    createRate("2026-09-30T23:00:00Z", 28.0484, "NON_DIRECT_DEBIT"))));
        List<PriceResult> results = new ArrayList<>();
        List<Instant> refreshTimes = new ArrayList<>();

        service.start();
        service.subscribeToPrices(results::add);
        service.subscribeToNextRefreshTime(refreshTimes::add);
        clock.tick();

        Instant dailyRetrievalTime = computeDailyRetrievalTime(LocalDate.of(2026, 11, 21));
        assertThat(results).singleElement().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileStart()).isEqualTo(SLOT);
            assertThat(prices.profileEnd()).isEqualTo(dailyRetrievalTime.plus(FORWARD_WINDOW));
            assertThat(prices.profile().pricePerInterval()).containsOnly(26.554);
        });
        assertThat(refreshTimes).containsExactly(dailyRetrievalTime);
    }

    @Test
    void prices_reachTheForwardWindowAheadOfNow_untilTheNextDailyRetrieval() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554))));
        List<PriceResult> results = new ArrayList<>();
        service.start();
        service.subscribeToPrices(results::add);
        clock.tick();

        clock.setTimeAndTick(computeDailyRetrievalTime(LocalDate.of(2026, 11, 21)).minus(OctopusPriceSlots.SLOT_LENGTH));

        assertThat(results).last().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileEnd()).isAfterOrEqualTo(clock.currentInstant().plus(FORWARD_WINDOW));
        });
    }

    @Test
    void failureWithNothingPublished_isReportedAtOnce_andRetried() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(failure("octopus is down"))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554))));
        List<PriceResult> results = new ArrayList<>();
        List<Instant> refreshTimes = new ArrayList<>();

        service.start();
        service.subscribeToPrices(results::add);
        service.subscribeToNextRefreshTime(refreshTimes::add);
        clock.tick();
        assertThat(results).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
        assertThat(refreshTimes).containsExactly(NOW.plus(OctopusPriceSlots.FAILURE_RETRY_DELAY));

        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);
        assertThat(results).last().isInstanceOf(Prices.class);
        assertThat(refreshTimes).last().isEqualTo(computeDailyRetrievalTime(LocalDate.of(2026, 11, 21)));
    }

    @Test
    void subscriberThatThrows_panicsTheGraph() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554))));
        List<String> reportedTasks = new ArrayList<>();
        taskFailureRegistry.addExceptionHandler((taskDescription, _) -> reportedTasks.add(taskDescription));

        service.start();
        service.subscribeToPrices(_ -> {
            throw new IllegalStateException("subscriber bug");
        });
        clock.tick();

        assertThat(reportedTasks).containsExactly(GRAPH_TASK_NAME_PREFIX + TARIFF);
    }

    @Test
    void failure_isLoggedNoHigherThanInfo() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any())).thenReturn(failure("octopus is down"));

        try (LogCapture log = LogCapture.capture(OctopusPublishedRatePriceServiceImpl.class.getPackageName(), Level.DEBUG)) {
            service.start();
            clock.tick();
            clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);

            assertThat(log.lines()).isNotEmpty().allSatisfy(line -> assertThat(line.level().isLessSpecificThan(Level.INFO)).isTrue());
        }
    }

    @Test
    void identicalFailures_arePublishedOnce() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any())).thenReturn(failure("octopus is down"));
        List<PriceResult> results = new ArrayList<>();

        service.start();
        service.subscribeToPrices(results::add);
        clock.tick();
        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);

        assertThat(results).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
    }

    @Test
    void failureAfterPrices_keepsThePrices_untilTheNextDailyRetrieval() {
        when(regionService.getStandardUnitRates(eq(PRODUCT), eq(TARIFF), any(), any()))
                .thenReturn(completedFuture(List.of(createRate("2026-09-30T23:00:00Z", 26.554))))
                .thenReturn(failure("octopus is down"));
        List<PriceResult> results = new ArrayList<>();
        service.start();
        service.subscribeToPrices(results::add);
        clock.tick();

        clock.setTimeAndTick(computeDailyRetrievalTime(LocalDate.of(2026, 11, 21)));
        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);
        assertThat(results).singleElement().isInstanceOf(Prices.class);

        clock.setTimeAndTick(computeDailyRetrievalTime(LocalDate.of(2026, 11, 22)));
        assertThat(results).last().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
    }

    /// @return the bands of a time-of-use tariff on each UK day from `first` to `last`: night 00:30–04:30 at 7.0, day otherwise at 30.0
    private static List<RateSegment> createGoBands(LocalDate first, LocalDate last) {
        List<RateSegment> segments = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            Instant midnight = date.atStartOfDay(UK_ZONE).toInstant();
            Instant nightStart = date.atTime(0, 30).atZone(UK_ZONE).toInstant();
            Instant nightEnd = date.atTime(4, 30).atZone(UK_ZONE).toInstant();
            segments.add(new RateSegment(midnight, nightStart, 30.0));
            segments.add(new RateSegment(nightStart, nightEnd, 7.0));
            segments.add(new RateSegment(nightEnd, date.plusDays(1).atStartOfDay(UK_ZONE).toInstant(), 30.0));
        }
        return segments;
    }

    private static double priceAt(Prices prices, String instant) {
        return prices.profile().pricePerInterval().get(prices.profileIndexOf(Instant.parse(instant)));
    }

    private static Instant computeDailyRetrievalTime(LocalDate date) {
        return date.atTime(OctopusPublishedRatePriceServiceImpl.DAILY_RETRIEVAL_TIME).atZone(UK_ZONE).toInstant();
    }
}
