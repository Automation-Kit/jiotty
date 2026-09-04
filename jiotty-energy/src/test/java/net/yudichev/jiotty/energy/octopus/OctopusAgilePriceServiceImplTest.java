package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.async.JobSchedulerImpl;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("OctalInteger")
@ExtendWith(MockitoExtension.class)
class OctopusAgilePriceServiceImplTest {

    private static final String PRODUCT_CODE = "AGILE-23-12-06";
    private static final String TARIFF_CODE = "E-1R-AGILE-23-12-06-A";

    private ProgrammableClock clock;
    @Mock
    private OctopusRegionService regionService;
    private OctopusAgilePriceServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc().withGlobalMdc(true);
        clock.setTime(time(1, 14, 0));
        // The scheduler runs on UTC while the service anchors its own job to Europe/London, which is the mismatch these tests exercise.
        var jobScheduler = new JobSchedulerImpl(clock, clock, ZoneOffset.UTC, new ListenerBackedTaskExceptionHandlerRegistry());
        var executor = clock.createSingleThreadedSchedulingExecutor("thread");
        var cache = new InMemoryTimeSeriesCache();
        service = new OctopusAgilePriceServiceImpl(() -> executor, clock, cache, jobScheduler, regionService, PRODUCT_CODE, TARIFF_CODE);

        lenient().when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                 .thenReturn(completedFuture(prices(time(1, 14, 0), time(1, 23, 00))));

        jobScheduler.start();
    }

    @Test
    void scenario() {
        service.start();
        clock.tick();
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        for (int day = 1; day < 4; day += 2) {
            reset(regionService);

            // normal case - after 16:00 on day 1 prices are available until 23:00 next day
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                    .thenReturn(completedFuture(prices(time(day, 16, 00), time(day + 1, 23, 00))));
            clock.setTimeAndTick(time(day, 16, 00).plusNanos(12));
            assertPricesAvailable(time(day, 16, 00), time(day + 1, 23, 00));
            reset(regionService);

            // next day, for some reason, prices are not yet available for the day after, even after 16:00
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                    .thenReturn(completedFuture(prices(time(day + 1, 16, 00), time(day + 1, 23, 00))));
            clock.setTimeAndTick(time(day + 1, 16, 00));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 1st poll - still not there
            clock.setTimeAndTick(time(day + 1, 16, 05));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 2nd poll - now we're talking
            reset(regionService);
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                    .thenReturn(completedFuture(prices(time(day + 1, 16, 00), time(day + 2, 23, 00))));
            clock.setTimeAndTick(time(day + 1, 16, 10));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 2, 23, 00));

            // ensure polling stopped — advance time, expect no further price changes
            clock.setTimeAndTick(time(day + 1, 16, 50));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 2, 23, 00));
        }
    }

    /// The publication window opens at 16:00 *London*, so under BST the job fires at 15:00Z — even though the scheduler itself is on UTC.
    @Test
    void publicationWindowAnchorsToLondonTimeUnderBst() {
        clock.setTime(bstTime(1, 14, 00));
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(bstTime(1, 14, 00), bstTime(1, 23, 00))));
        service.start();
        clock.tick();
        assertPricesAvailable(bstTime(1, 14, 00), bstTime(1, 23, 00));

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(bstTime(1, 16, 00), bstTime(2, 23, 00))));

        clock.setTimeAndTick(Instant.parse("2024-07-01T14:59:59Z"));
        assertPricesAvailable(bstTime(1, 14, 00), bstTime(1, 23, 00));

        clock.setTimeAndTick(Instant.parse("2024-07-01T15:00:00Z"));
        assertPricesAvailable(bstTime(1, 16, 00), bstTime(2, 23, 00));
    }

    /// A publication that misses the window entirely still has all night to arrive, and the overnight plan needs it — so the poll slows past the day boundary
    /// rather than stopping, and picks the prices up whenever they land.
    @Test
    void pollContinuesPastTheDayBoundaryUntilPricesArrive() {
        service.start();
        clock.tick();

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertPricesAvailable(time(1, 16, 00), time(1, 23, 00));

        // still nothing an hour and a half after the boundary, and the app is still asking
        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(2, 00, 30));
        verify(regionService, atLeast(1)).getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any());

        // they finally land overnight
        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(2, 23, 00))));
        clock.setTimeAndTick(time(2, 01, 00));
        assertPricesAvailable(time(1, 16, 00), time(2, 23, 00));

        // and the polling stops once they have
        reset(regionService);
        lenient().when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                 .thenReturn(completedFuture(prices(time(1, 16, 00), time(2, 23, 00))));
        clock.setTimeAndTick(time(2, 04, 00));
        verify(regionService, never()).getStandardUnitRates(any(), any(), any(), any());
    }

    /// A poll left over from a day whose prices never arrived must not stop the next window's retrieval from happening.
    @Test
    void publicationWindowRetrievesWhileAnEarlierPollIsStillPending() {
        service.start();
        clock.tick();

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertPricesAvailable(time(1, 16, 00), time(1, 23, 00));

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(2, 16, 00), time(3, 23, 00))));
        clock.setTimeAndTick(time(2, 16, 00));
        assertPricesAvailable(time(2, 16, 00), time(3, 23, 00));
    }

    /// Stopping the service abandons a poll it had armed, so a stopped tariff makes no further calls on Octopus.
    @Test
    void stopCancelsAnArmedPoll() {
        service.start();
        clock.tick();

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertPricesAvailable(time(1, 16, 00), time(1, 23, 00));

        service.stop();
        clock.tick();

        reset(regionService);
        lenient().when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                 .thenReturn(completedFuture(prices(time(1, 16, 00), time(2, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 05));
        verify(regionService, never()).getStandardUnitRates(any(), any(), any(), any());
    }

    /// Polling over a profile Octopus has not extended yet must not wake every subscriber every five minutes.
    @Test
    void unchangedPricesAreNotRepublished() {
        service.start();
        clock.tick();

        var notifications = new ArrayList<Either<Prices, EnergyPriceService.Failure>>();
        Closeable subscription = service.subscribeToPrices(notifications::add);
        assertThat(notifications).describedAs("image on subscribe").hasSize(1);

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertThat(notifications).describedAs("after the window opened").hasSize(2);

        clock.setTimeAndTick(time(1, 16, 05));
        clock.setTimeAndTick(time(1, 16, 10));
        clock.setTimeAndTick(time(1, 16, 15));
        assertThat(notifications).describedAs("after three polls returning the same prices").hasSize(2);

        subscription.close();
    }

    /// The refresh expectation is what lets a consumer tell a shortfall that is about to be filled from one that will stand, so it tracks the three states the
    /// service can be in: everything published, a publication due, and a hole no publication reaches.
    @Test
    void nextRefreshTimeReportsWhetherTheProfileIsAboutToGrow() {
        var received = new ArrayList<Instant>();
        service.start();
        service.subscribeToNextRefreshTime(received::add);
        clock.tick();

        // 14:00 with prices to tonight's boundary: everything Octopus owes has arrived, so the profile grows when today's window opens
        assertThat(received).containsExactly(time(1, 16, 00));

        // after the window opens with tomorrow's prices still missing, one is due and would extend the profile
        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 16, 00), time(1, 23, 00))));
        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertThat(received).describedAs("a publication is due, so the profile grows at the next poll")
                            .containsExactly(time(1, 16, 00), time(1, 16, 05));
    }

    /// A profile stopping short of the supplier day's end has a hole that the day now being published starts after, so waiting for that publication would not
    /// close it and the profile grows no earlier than the next window.
    @Test
    void nextRefreshTimeIsTheNextWindowWhenNoPublicationReachesTheGap() {
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(1, 14, 00), time(1, 20, 00))));
        var received = new ArrayList<Instant>();
        service.start();
        service.subscribeToNextRefreshTime(received::add);
        clock.tick();

        clock.setTimeAndTick(time(1, 16, 00).plusNanos(12));
        assertThat(received).last().isEqualTo(time(2, 16, 00));
    }

    @Test
    void octopusFailure() {
        service.start();
        clock.tick();
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any())).thenReturn(CompletableFutures.failure("oops"));
        clock.setTimeAndTick(time(1, 16, 00));
        // then no new prices as octopus call failed
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        // problems continue until day 2's regular call — and the retries-overran point lands a `PriceRetrievalError` on the result stream
        clock.setTimeAndTick(time(2, 16, 00));
        Either<Prices, EnergyPriceService.Failure> lastResult = service.getPrices().orElseThrow();
        assertThat(lastResult.getRight()).hasValueSatisfying(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                        error -> assertThat(error.cause()).hasMessageContaining("oops")));

        // octopus recovered
        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(2, 16, 00), time(3, 23, 00))));
        clock.setTimeAndTick(time(2, 16, 15));
        assertPricesAvailable(time(2, 16, 00), time(3, 23, 00));
    }

    private void assertPricesAvailable(Instant from, Instant to) {
        Either<Prices, EnergyPriceService.Failure> result = service.getPrices().orElseThrow();
        Prices prices = result.getLeft().orElseThrow();
        assertThat(prices.profileStart()).describedAs("profile start").isEqualTo(from);
        assertThat(prices.profileEnd()).describedAs("profile end").isEqualTo(to);
    }

    private static List<StandardUnitRate> prices(Instant from, Instant to) {
        var result = new ArrayList<StandardUnitRate>();
        Instant validTo = to;
        while (validTo.isAfter(from)) {
            result.add(StandardUnitRate.builder().setValidFrom(validTo.minus(30, MINUTES)).setValidTo(validTo).setValueExcVat(13).setValueIncVat(14).build());
            validTo = validTo.minus(30, MINUTES);
        }
        return result;
    }

    private static Instant time(int day, int hour, int min) {
        return LocalDateTime.of(2024, 1, day, hour, min, 00).atZone(AgilePublicationWindow.ZONE).toInstant();
    }

    /// The same wall clock as [#time], in July — where Europe/London is an hour ahead of UTC and a job resolved in the wrong zone shows up.
    private static Instant bstTime(int day, int hour, int min) {
        return LocalDateTime.of(2024, 7, day, hour, min, 00).atZone(AgilePublicationWindow.ZONE).toInstant();
    }
}
