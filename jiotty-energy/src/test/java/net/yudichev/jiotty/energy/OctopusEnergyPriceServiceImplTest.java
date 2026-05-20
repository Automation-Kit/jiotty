package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.JobSchedulerImpl;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
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

import static java.time.temporal.ChronoUnit.DAYS;
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
class OctopusEnergyPriceServiceImplTest {

    private static final ZoneId ZONE_ID = ZoneId.of("Europe/London");
    private static final String ACCOUNT_ID = "A-AAAAAAAA";
    private static final String API_KEY = "sk_test_xxxxxxxxxxxx";
    private static final String PRODUCT_CODE = "AGILE-23-12-06";
    private static final String TARIFF_CODE = "E-1R-AGILE-23-12-06-A";
    private static final char REGION = 'A';

    private ProgrammableClock clock;
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusRegionService regionService;
    private OctopusEnergyPriceServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc().withGlobalMdc(true);
        clock.setTime(time(1, 14, 0));
        var jobScheduler = new JobSchedulerImpl(clock, clock, ZoneOffset.UTC);
        var executor = clock.createSingleThreadedSchedulingExecutor("thread");
        service = new OctopusEnergyPriceServiceImpl(() -> executor, clock, octopusEnergy, ACCOUNT_ID, API_KEY, jobScheduler, ZONE_ID);

        // The account future resolves to the same Agile-tariff payload across the success scenarios; tests vary the rates returned by the region service.
        // `incompatibleTariff_emitsFailureWithoutCallingRegionService` overrides this with a non-Agile tariff payload.
        when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(accountService);
        lenient().when(accountService.getAccount()).thenReturn(completedFuture(agileAccountData()));
        lenient().when(octopusEnergy.region(REGION)).thenReturn(regionService);

        lenient().when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE),
                                                          eq(clock.currentInstant()), eq(clock.currentInstant().plus(2, DAYS))))
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
            when(octopusEnergy.region(REGION)).thenReturn(regionService);

            // normal case - after 16:00 on day 1 prices are available until 23:00 next day
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), eq(time(day, 16, 05)), eq(time(day + 2, 16, 05))))
                    .thenReturn(completedFuture(prices(time(day, 16, 00), time(day + 1, 23, 00))));
            clock.setTimeAndTick(time(day, 16, 05).plusNanos(12));
            assertPricesAvailable(time(day, 16, 00), time(day + 1, 23, 00));
            reset(regionService);
            when(octopusEnergy.region(REGION)).thenReturn(regionService);

            // next day, for some reason, prices are not yet available for the day after, even after 16:00
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), eq(time(day + 1, 16, 05)), eq(time(day + 3, 16, 05))))
                    .thenReturn(completedFuture(prices(time(day + 1, 16, 00), time(day + 1, 23, 00))));
            clock.setTimeAndTick(time(day + 1, 16, 05));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 1st retry - still not there
            clock.setTimeAndTick(time(day + 1, 16, 20));
            assertPricesAvailable(time(day + 1, 16, 00), time(day + 1, 23, 00));

            // 2nd retry - now we're talking
            reset(regionService);
            when(octopusEnergy.region(REGION)).thenReturn(regionService);
            when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), eq(time(day + 1, 16, 05)), eq(time(day + 3, 16, 05))))
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
        when(octopusEnergy.region(REGION)).thenReturn(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), any(), any())).thenReturn(CompletableFutures.failure("oops"));
        clock.setTimeAndTick(time(1, 16, 05));
        // then no new prices as octopus call failed
        assertPricesAvailable(time(1, 14, 00), time(1, 23, 00));

        // problems continue until day 2's regular call — and the retries-overran point lands a `PriceRetrievalError` on the result stream
        clock.setTimeAndTick(time(2, 16, 05));
        var lastResult = service.getResult().orElseThrow();
        assertThat(lastResult.getRight()).hasValueSatisfying(failure ->
                                                                     assertThat(failure).isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                                                                                                error -> assertThat(error.cause()).hasMessageContaining(
                                                                                                                        "oops")));

        // octopus recovered
        reset(regionService);
        when(octopusEnergy.region(REGION)).thenReturn(regionService);
        when(regionService.getStandardUnitRates(eq(PRODUCT_CODE), eq(TARIFF_CODE), eq(time(2, 16, 05)), any()))
                .thenReturn(completedFuture(prices(time(2, 16, 00), time(3, 23, 00))));
        clock.setTimeAndTick(time(2, 16, 20));
        assertPricesAvailable(time(2, 16, 00), time(3, 23, 00));
    }

    @Test
    void incompatibleTariff_emitsFailureWithoutCallingRegionService() {
        // Override the default Agile account data with a non-Agile tariff (Octopus Go) so the price service short-circuits to IncompatibleTariff.
        var goTariffCode = "E-1R-GO-VAR-22-10-14-A";
        when(accountService.getAccount()).thenReturn(completedFuture(accountDataWithTariff(goTariffCode)));

        service.start();
        clock.tick();

        Either<Prices, EnergyPriceService.Failure> result = service.getResult().orElseThrow();
        assertThat(result.getRight()).contains(new EnergyPriceService.Failure.IncompatibleTariff(goTariffCode));
    }

    private void assertPricesAvailable(Instant from, Instant to) {
        var result = service.getResult().orElseThrow();
        var prices = result.getLeft().orElseThrow();
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

    /// Builds an [OctopusAccountData] whose only electricity meter point's current tariff has the Agile `(productCode, tariffCode, region)` tuple this test
    /// uses. The tariff window is wide enough to cover every clock advance in the scenarios.
    private static OctopusAccountData agileAccountData() {
        return accountDataWithTariff(TARIFF_CODE);
    }

    private static OctopusAccountData accountDataWithTariff(String tariffCode) {
        Tariff tariff = Tariff.builder()
                              .setTariffCode(tariffCode)
                              .setValidFrom(Instant.parse("2020-01-01T00:00:00Z"))
                              .setValidTo(Instant.parse("2099-01-01T00:00:00Z"))
                              .build();
        ElectricityMeterPoint meterPoint = ElectricityMeterPoint.builder()
                                                                .addTariffs(tariff)
                                                                .setMpan("9999999999999")
                                                                .build();
        AccountProperty property = AccountProperty.builder()
                                                  .addElectricityMeterPoints(meterPoint)
                                                  .build();
        return OctopusAccountData.builder()
                                 .addProperties(property)
                                 .build();
    }

    private static Instant time(int day, int hour, int min) {
        return LocalDateTime.of(2024, 1, day, hour, min, 00).atZone(ZONE_ID).toInstant();
    }
}
