package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.JobSchedulerImpl;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static java.time.temporal.ChronoUnit.MINUTES;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SuppressWarnings("OctalInteger")
@ExtendWith(MockitoExtension.class)
class OctopusAgilePriceServiceImplTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Europe/London");
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
        var jobScheduler = new JobSchedulerImpl(clock, clock, ZoneOffset.UTC);
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
            clock.setTimeAndTick(time(day, 16, 05).plusNanos(12));
            assertPricesAvailable(time(day, 16, 00), time(day + 1, 23, 00));
            reset(regionService);

            // next day, for some reason, prices are not yet available for the day after, even after 16:00
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                    .thenReturn(completedFuture(prices(time(day + 1, 16, 00), time(day + 1, 23, 00))));
            clock.setTimeAndTick(time(day + 1, 16, 05));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 1st retry - still not there
            clock.setTimeAndTick(time(day + 1, 16, 20));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 2nd retry - now we're talking
            reset(regionService);
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                    .thenReturn(completedFuture(prices(time(day + 1, 16, 00), time(day + 2, 23, 00))));
            clock.setTimeAndTick(time(day + 1, 16, 35));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 2, 23, 00));

            // ensure retries stopped — advance time, expect no further price changes
            clock.setTimeAndTick(time(day + 1, 16, 50));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 2, 23, 00));
        }
    }

    @Test
    void octopusFailure() {
        service.start();
        clock.tick();
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any())).thenReturn(CompletableFutures.failure("oops"));
        clock.setTimeAndTick(time(1, 16, 05));
        // then no new prices as octopus call failed
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        // problems continue until day 2's regular call — and the retries-overran point lands a `PriceRetrievalError` on the result stream
        clock.setTimeAndTick(time(2, 16, 05));
        Either<Prices, EnergyPriceService.Failure> lastResult = service.getPrices().orElseThrow();
        assertThat(lastResult.getRight()).hasValueSatisfying(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                        error -> assertThat(error.cause()).hasMessageContaining("oops")));

        // octopus recovered
        reset(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any()))
                .thenReturn(completedFuture(prices(time(2, 16, 00), time(3, 23, 00))));
        clock.setTimeAndTick(time(2, 16, 20));
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
        return LocalDateTime.of(2024, 1, day, hour, min, 00).atZone(ZONE_ID).toInstant();
    }
}
