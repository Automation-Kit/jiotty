package net.yudichev.jiotty.energy.octopus;

import com.fasterxml.jackson.core.JsonParseException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ConsumptionRow;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeter;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.StandingCharge;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.EnergyPriceService;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffPeriod;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.apache.logging.log4j.Level;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Throwables.getCausalChain;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;
import static net.yudichev.jiotty.energy.octopus.TestPrices.FORWARD_WINDOW;
import static net.yudichev.jiotty.energy.octopus.TestPrices.FORWARD_WINDOW_SLOTS;
import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusEnergyProviderServiceTest {

    private static final String ACCOUNT_ID = "A-AAAAAAAA";
    private static final String API_KEY = "sk_test_xxxxxxxxxxxx";
    private static final String MPAN = "9999999999999";
    private static final String METER_SERIAL = "99XXX99999";
    private static final String AGILE_TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final String AGILE_TARIFF_B = "E-1R-AGILE-23-12-06-B";
    private static final String TRACKER_TARIFF_A = "E-1R-SILVER-24-12-31-A";
    private static final String FLEXIBLE_TARIFF_A = "E-1R-VAR-22-11-01-A";
    private static final String IOG_TARIFF_A = "E-1R-IOG-SMB-VAR-26-11-04-A";
    private static final Instant TARIFF_VALID_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant TARIFF_VALID_TO = Instant.parse("2099-01-01T00:00:00Z");
    /// The clock's time when each test starts, which is when the first account poll runs.
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    /// A switch between agreements inside [TestPrices#FORWARD_WINDOW] from [#START].
    private static final Instant SWITCH = Instant.parse("2024-01-03T00:00:00Z");
    private static final int SLOTS_TO_SWITCH = 76;
    private final InMemoryTimeSeriesCache cache = new InMemoryTimeSeriesCache();
    private final ListenerBackedTaskExceptionHandlerRegistry taskFailureRegistry = new ListenerBackedTaskExceptionHandlerRegistry();
    private ProgrammableClock clock;
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusRegionService regionService;
    @Mock
    private OctopusTariffPriceServiceRegistry tariffRegistry;
    @Mock
    private PriceForecastServiceRegistry priceForecastRegistry;
    private FakeTariffPriceService agileRegionA;
    private FakeTariffPriceService agileRegionB;
    private FakeTariffPriceService flexibleRegionA;
    private FakeForecastPriceService forecastRegionA;
    private FakeForecastPriceService forecastRegionB;
    private OctopusEnergyProviderService service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(START);
        SchedulingExecutor executor = clock.createSingleThreadedSchedulingExecutor("provider");

        agileRegionA = new FakeTariffPriceService();
        agileRegionB = new FakeTariffPriceService();
        flexibleRegionA = new FakeTariffPriceService();
        var iogRegionA = new FakeTariffPriceService();
        forecastRegionA = new FakeForecastPriceService();
        forecastRegionB = new FakeForecastPriceService();

        lenient().when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(accountService);
        lenient().when(tariffRegistry.forTariff(AGILE_TARIFF_A)).thenReturn(createPricedSource(agileRegionA, TariffPricing.DYNAMIC, false));
        lenient().when(tariffRegistry.forTariff(AGILE_TARIFF_B)).thenReturn(createPricedSource(agileRegionB, TariffPricing.DYNAMIC, false));
        lenient().when(tariffRegistry.forTariff(FLEXIBLE_TARIFF_A)).thenReturn(createPricedSource(flexibleRegionA, TariffPricing.PUBLISHED, false));
        lenient().when(tariffRegistry.forTariff(IOG_TARIFF_A)).thenReturn(createPricedSource(iogRegionA, TariffPricing.PUBLISHED, true));
        lenient().when(tariffRegistry.forTariff(TRACKER_TARIFF_A)).thenReturn(completedFuture(TariffPriceSource.Unsupported.INSTANCE));
        lenient().when(priceForecastRegistry.forRegion('A')).thenReturn(forecastRegionA);
        lenient().when(priceForecastRegistry.forRegion('B')).thenReturn(forecastRegionB);

        service = createService(executor);
    }

    // ---- Price routing ----

    @Test
    void initialAgileTariff_routesToCorrectDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();

        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(forecastRegionA.subscribers).hasSize(1);
        assertThat(agileRegionB.subscribers).isEmpty();

        // The tariff's price service publishes, and the service delivers its prices to subscribers.
        List<PriceResult> deliveries = new ArrayList<>();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        Prices realPrices = prices("10:00", 1, 5.0);
        agileRegionA.publish(realPrices);
        clock.tick();

        assertThat(deliveries).containsExactly(realPrices);
    }

    @Test
    void forecastPricesArrive_areAppendedAfterTheRealOnes() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        service.start();
        clock.tick();
        List<PriceResult> deliveries = new ArrayList<>();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        agileRegionA.publish(prices("10:00", 1, 5.0));
        clock.tick();
        forecastRegionA.publish(prices("10:00", 0, 7.0, 9.0));
        clock.tick();

        assertThat(deliveries).last().isEqualTo(prices("10:00", 1, 5.0, 9.0));
    }

    @Test
    void exportMeterPointFirst_tariffTakenFromImportMeter() {
        // Export meter point listed first (region A), import meter point second (region B). Region/tariff must come from the import meter even though the
        // export meter is listed first.
        when(accountService.getAccount()).thenReturn(completedFuture(accountWithExportThenImport(AGILE_TARIFF_A, AGILE_TARIFF_B)));

        service.start();
        clock.tick();

        assertThat(agileRegionB.subscribers).describedAs("routed to the import meter's tariff/region").hasSize(1);
        assertThat(forecastRegionB.subscribers).hasSize(1);
        assertThat(agileRegionA.subscribers).describedAs("export meter's tariff/region must be ignored").isEmpty();
        assertThat(forecastRegionA.subscribers).isEmpty();
    }

    @Test
    void exportMeterPointOnly_isARetrievalError() {
        ElectricityMeterPoint exportMeterPoint = meterPoint(true, createTariffOverTheTestWindow(AGILE_TARIFF_A));
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithMeterPoints(exportMeterPoint)));
        List<PriceResult> deliveries = new ArrayList<>();

        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        assertThat(deliveries).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
        assertThat(agileRegionA.subscribers).isEmpty();
    }

    @Test
    void tariffChange_reroutesOctopusDelegate() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        // Next 12h poll: the account's agreements are now on tariff B (different region).
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_B)));
        advanceToPoll(1);

        assertThat(agileRegionA.subscribers).describedAs("old subscription closed").isEmpty();
        assertThat(agileRegionB.subscribers).describedAs("new subscription opened").hasSize(1);
        assertThat(forecastRegionA.subscribers).describedAs("old forecast subscription closed").isEmpty();
        assertThat(forecastRegionB.subscribers).describedAs("new forecast subscription opened").hasSize(1);
    }

    /// The forecast fills slots past the real prices but never makes the real ones reach further, so the next refresh time is the one that the Agile
    /// tariff's [OctopusTariffPriceService] reports.
    @Test
    void agileTariffRefreshTime_isForwardedToSubscribers() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        service.start();
        clock.tick();

        List<Instant> deliveries = new ArrayList<>();
        service.subscribeToNextRefreshTime(deliveries::add);
        clock.tick();
        assertThat(deliveries).describedAs("nothing until the tariff's price service reports a refresh time").isEmpty();

        agileRegionA.publishRefreshTime(Instant.parse("2024-01-02T16:00:00Z"));
        clock.tick();
        assertThat(deliveries).containsExactly(Instant.parse("2024-01-02T16:00:00Z"));

        // after the account moves to AGILE_TARIFF_B, the refresh time of its price service is delivered
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_B)));
        advanceToPoll(1);
        agileRegionB.publishRefreshTime(Instant.parse("2024-01-03T16:00:00Z"));
        clock.tick();
        assertThat(deliveries).containsExactly(Instant.parse("2024-01-02T16:00:00Z"), Instant.parse("2024-01-03T16:00:00Z"));
    }

    @Test
    void accountFetchFailure_emitsPriceRetrievalError() {
        // With no retries, a failed account fetch is delivered to price subscribers as a PriceRetrievalError carrying the cause.
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        assertThat(deliveries).singleElement().isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                                                      err -> assertThat(err.cause()).hasMessageContaining("octopus is down"));
    }

    @Test
    void identicalFailureMessage_isDeduplicated() {
        // Same exception type + message on the second poll → dedup suppresses the second emission.
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        assertThat(deliveries).hasSize(1);

        // Next 12h poll: same outage continues, identical exception text.
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(1);

        assertThat(deliveries).describedAs("dedup should suppress a second identical PriceRetrievalError").hasSize(1);
    }

    @Test
    void differentFailureMessage_isNotDeduplicated() {
        when(accountService.getAccount()).thenReturn(failure("octopus 500"));

        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        assertThat(deliveries).hasSize(1);

        when(accountService.getAccount()).thenReturn(failure("connection reset"));
        advanceToPoll(1);

        assertThat(deliveries).describedAs("a different exception message should fire a fresh PriceRetrievalError").hasSize(2);
    }

    @Test
    void failedFirstLoadThenSuccess_routesToTheTariff() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        assertThat(deliveries).hasSize(1);

        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        advanceToPoll(1);

        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(forecastRegionA.subscribers).hasSize(1);
    }

    @Test
    void failedRefreshAfterALoad_leavesTheAgilePricesAsTheyAre() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        List<PriceResult> priceResults = new ArrayList<>();
        service.start();
        service.subscribeToPrices(priceResults::add);
        clock.tick();
        Prices realPrices = prices("10:00", 1, 5.0);
        agileRegionA.publish(realPrices);
        clock.tick();

        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(1);
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        advanceToPoll(2);

        assertThat(priceResults).containsExactly(realPrices);
        assertThat(service.getPrices()).contains(realPrices);
        assertThat(agileRegionA.subscribers).describedAs("the delegate is kept through the failed refresh").hasSize(1);
    }

    @Test
    void failedRefreshAfterALoad_keepsTheIncompatibleTariff() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(TRACKER_TARIFF_A)));
        List<PriceResult> priceResults = new ArrayList<>();
        service.start();
        service.subscribeToPrices(priceResults::add);
        clock.tick();

        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(1);
        when(accountService.getAccount()).thenReturn(completedFuture(account(TRACKER_TARIFF_A)));
        advanceToPoll(2);

        assertThat(priceResults).containsExactly(new EnergyPriceService.Failure.IncompatibleTariff(TRACKER_TARIFF_A));
        assertThat(service.getPrices()).contains(new EnergyPriceService.Failure.IncompatibleTariff(TRACKER_TARIFF_A));
    }

    @Test
    void currentAgileAgreementWithAFutureOpenEndedOne_routesToTheAgileTariff() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, "2023-06-01T00:00:00Z", "2024-06-01T00:00:00Z"),
                createTariff(FLEXIBLE_TARIFF_A, "2024-06-01T00:00:00Z", null))));

        service.start();
        clock.tick();

        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(flexibleRegionA.subscribers).describedAs("an agreement past FORWARD_WINDOW from now is not resolved").isEmpty();
    }

    @Test
    void currentOpenEndedFlexibleAgreement_routesToItsPublishedRates() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, "2023-01-01T00:00:00Z", "2023-12-01T00:00:00Z"),
                createTariff(FLEXIBLE_TARIFF_A, "2023-12-01T00:00:00Z", null))));
        List<PriceResult> priceResults = new ArrayList<>();

        service.start();
        service.subscribeToPrices(priceResults::add);
        clock.tick();
        Prices flexiblePrices = createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554);
        flexibleRegionA.publish(flexiblePrices);
        clock.tick();

        assertThat(priceResults).containsExactly(flexiblePrices);
        assertThat(agileRegionA.subscribers).isEmpty();
        assertThat(forecastRegionA.subscribers).describedAs("no Agile agreement in the window, so no forecast").isEmpty();
    }

    @Test
    void priceSubscriberThatThrows_isReportedUnderTheDeliveryName() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(FLEXIBLE_TARIFF_A)));
        List<String> reportedTasks = new ArrayList<>();
        taskFailureRegistry.addExceptionHandler((taskDescription, _) -> reportedTasks.add(taskDescription));

        service.start();
        service.subscribeToPrices(_ -> {
            throw new IllegalStateException("subscriber bug");
        });
        clock.tick();
        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();

        assertThat(reportedTasks).containsExactly("Delivering the Octopus prices");
    }

    @Test
    void overlappingAgreements_rejoinAtTheEarliestBoundary() {
        Instant earlierEnd = Instant.parse("2024-01-01T11:00:00Z");
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                // starts after the Agile agreement starts and ends before the Agile agreement ends
                createTariff(FLEXIBLE_TARIFF_A, Instant.parse("2023-12-01T00:00:00Z"), earlierEnd))));
        service.start();
        clock.tick();
        assertThat(flexibleRegionA.subscribers).hasSize(1);

        clock.setTimeAndTick(earlierEnd);

        assertThat(flexibleRegionA.subscribers).describedAs("the flexible agreement has left the window").isEmpty();
    }

    @Test
    void accountWithNoMeterPoints_emitsAPriceRetrievalError() {
        // An empty account (no electricity meter points) has no agreement in force, so the service delivers a PriceRetrievalError.
        when(accountService.getAccount()).thenReturn(completedFuture(OctopusAccountData.builder().build()));

        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        assertThat(deliveries).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
    }

    @Test
    void unsupportedTariff_emitsIncompatibleTariffAndClosesDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        List<PriceResult> deliveries = new ArrayList<>();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        deliveries.clear();

        // Next poll: the account is now on a tariff that cannot be priced.
        when(accountService.getAccount()).thenReturn(completedFuture(account(TRACKER_TARIFF_A)));
        advanceToPoll(1);

        assertThat(agileRegionA.subscribers).describedAs("Octopus subscription closed on IncompatibleTariff").isEmpty();
        assertThat(forecastRegionA.subscribers).describedAs("forecast subscription closed on IncompatibleTariff").isEmpty();
        assertThat(deliveries).contains(new EnergyPriceService.Failure.IncompatibleTariff(TRACKER_TARIFF_A));
    }

    // ---- Joining agreements ----

    /// An account on Agile until [#SWITCH] and on Flexible, with no end date, from then on.
    @Test
    void agileThenFlexible_joinsTheTwoAtTheSwitch() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(FLEXIBLE_TARIFF_A, SWITCH, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        agileRegionA.publish(createFlatPrices(START, SLOTS_TO_SWITCH, 12.0));
        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();

        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileStart()).isEqualTo(START);
            assertThat(prices.profileEnd()).isEqualTo(START.plus(FORWARD_WINDOW));
            assertThat(prices.profile().pricePerInterval().get(SLOTS_TO_SWITCH - 1)).isEqualTo(12.0);
            assertThat(prices.profile().pricePerInterval().get(SLOTS_TO_SWITCH)).isEqualTo(26.554);
            assertThat(prices.profile().idxOfPredictedPriceStart()).isEqualTo(FORWARD_WINDOW_SLOTS);
        });
        assertThat(forecastRegionA.subscribers).describedAs("the Agile agreement is in the window").hasSize(1);
    }

    @Test
    void flexibleThenAgile_joinsTheTwoAtTheSwitch() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(FLEXIBLE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(AGILE_TARIFF_A, SWITCH, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        agileRegionA.publish(createFlatPrices(START, 100, 12.0));
        clock.tick();

        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profile().pricePerInterval().get(SLOTS_TO_SWITCH - 1)).isEqualTo(26.554);
            assertThat(prices.profile().pricePerInterval().get(SLOTS_TO_SWITCH)).isEqualTo(12.0);
            assertThat(prices.profileEnd()).isEqualTo(START.plus(OctopusPriceSlots.SLOT_LENGTH.multipliedBy(100)));
        });
    }

    /// The Agile stretch takes the forecast after its actual prices; the Flexible stretch after the switch does not.
    @Test
    void forecast_isJoinedToTheAgileStretchOnly() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(FLEXIBLE_TARIFF_A, SWITCH, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        agileRegionA.publish(createFlatPrices(START, 10, 12.0));
        forecastRegionA.publish(createFlatPrices(START, 200, 30.0));
        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();

        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profile().pricePerInterval().get(9)).isEqualTo(12.0);
            assertThat(prices.profile().pricePerInterval().get(10)).isEqualTo(30.0);
            assertThat(prices.profile().pricePerInterval().get(SLOTS_TO_SWITCH)).isEqualTo(26.554);
            assertThat(prices.profile().idxOfPredictedPriceStart()).isEqualTo(10);
        });
    }

    @Test
    void supportedThenUnsupported_endsTheProfileAtTheSwitch_withNoRefreshExpected() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(FLEXIBLE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(TRACKER_TARIFF_A, SWITCH, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        List<Instant> refreshTimes = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        service.subscribeToNextRefreshTime(refreshTimes::add);
        clock.tick();

        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();

        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> assertThat(prices.profileEnd()).isEqualTo(SWITCH));
        assertThat(refreshTimes).last().isEqualTo(EnergyPriceService.NO_REFRESH_EXPECTED);
    }

    @Test
    void laterStretchArrivingLate_extendsTheProfileWhenItComes() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(FLEXIBLE_TARIFF_A, SWITCH, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        List<Instant> refreshTimes = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        service.subscribeToNextRefreshTime(refreshTimes::add);
        clock.tick();

        agileRegionA.publish(createFlatPrices(START, SLOTS_TO_SWITCH, 12.0));
        flexibleRegionA.publishRefreshTime(Instant.parse("2024-01-01T18:00:00Z"));
        clock.tick();
        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> assertThat(prices.profileEnd()).isEqualTo(SWITCH));
        assertThat(refreshTimes).describedAs("the Flexible stretch owns the slot at the profile's end")
                                .last()
                                .isEqualTo(Instant.parse("2024-01-01T18:00:00Z"));

        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();
        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> assertThat(prices.profileEnd()).isAfter(SWITCH));
    }

    @Test
    void agreementBoundary_rejoinsWithoutWaitingForThePoll() {
        Instant switchAt = Instant.parse("2024-01-01T12:00:00Z");
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), switchAt),
                createTariff(FLEXIBLE_TARIFF_A, switchAt, null))));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        agileRegionA.publish(createFlatPrices(START, 4, 12.0));
        flexibleRegionA.publish(createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554));
        clock.tick();
        assertThat(forecastRegionA.subscribers).hasSize(1);

        clock.setTimeAndTick(switchAt);

        assertThat(agileRegionA.subscribers).describedAs("the Agile agreement has left the window").isEmpty();
        assertThat(forecastRegionA.subscribers).describedAs("no Agile agreement remains").isEmpty();
        assertThat(deliveries).last().isInstanceOfSatisfying(Prices.class, prices -> {
            assertThat(prices.profileStart()).isEqualTo(switchAt);
            assertThat(prices.profile().pricePerInterval().getFirst()).isEqualTo(26.554);
        });
    }

    /// An agreement that starts past the forward window when the account is fetched is joined as soon as the window reaches it, well before the next poll,
    /// whether a switch between the agreements inside the window comes before or after that.
    ///
    /// @param switchAfterStart when the switch from the Flexible agreement to the first Agile one comes, after [#START]
    @ParameterizedTest
    @ValueSource(ints = {2, 6})
    void agreementEnteringTheWindow_isJoinedWithoutWaitingForThePoll(int switchAfterStart) {
        Instant laterAgileStart = START.plus(FORWARD_WINDOW).plus(Duration.ofHours(4));
        Instant switchToAgile = START.plus(Duration.ofHours(switchAfterStart));
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(FLEXIBLE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), switchToAgile),
                createTariff(AGILE_TARIFF_B, switchToAgile, laterAgileStart),
                createTariff(AGILE_TARIFF_A, laterAgileStart, null))));
        List<TariffTimeline> timelines = new ArrayList<>();
        service.start();
        service.subscribeToTariffTimeline(timelines::add);
        clock.tick();
        assertThat(agileRegionA.subscribers).describedAs("the later Agile agreement starts past the window").isEmpty();

        clock.setTimeAndTick(laterAgileStart.minus(FORWARD_WINDOW).plus(OctopusPriceSlots.SLOT_LENGTH));

        assertThat(agileRegionA.subscribers).hasSize(1);
        TariffPeriod laterAgilePeriod = createPeriod(laterAgileStart, null, TariffPricing.DYNAMIC, false);
        assertThat(timelines).last().satisfies(timeline -> assertThat(timeline.periods()).last().isEqualTo(laterAgilePeriod));
    }

    @Test
    void failedResolution_publishesARetrievalError_andIsRetried() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(FLEXIBLE_TARIFF_A)));
        when(tariffRegistry.forTariff(FLEXIBLE_TARIFF_A)).thenReturn(failedFuture(new RuntimeException("products endpoint is down")))
                                                         .thenReturn(createPricedSource(flexibleRegionA, TariffPricing.PUBLISHED, false));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        assertThat(deliveries).singleElement().isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                                                      error -> assertThat(error.cause()).hasMessage("products endpoint is down"));

        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);
        Prices flexiblePrices = createFlatPrices(START, FORWARD_WINDOW_SLOTS, 26.554);
        flexibleRegionA.publish(flexiblePrices);
        clock.tick();

        assertThat(deliveries).last().isEqualTo(flexiblePrices);
    }

    @Test
    void timeline_describesEveryAgreementInTheWindow_andComesBeforeThePrices() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(IOG_TARIFF_A, SWITCH, null))));
        List<Object> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToTariffTimeline(deliveries::add);
        service.subscribeToPrices(deliveries::add);
        agileRegionA.publish(createFlatPrices(START, SLOTS_TO_SWITCH, 12.0));
        clock.tick();

        assertThat(deliveries).satisfiesExactly(
                first -> assertThat(first).isEqualTo(new TariffTimeline(ImmutableList.of(
                        createPeriod(Instant.parse("2023-06-01T00:00:00Z"), SWITCH, TariffPricing.DYNAMIC, false),
                        createPeriod(SWITCH, null, TariffPricing.PUBLISHED, true)))),
                second -> assertThat(second).isInstanceOf(Prices.class));
    }

    // ---- Account details ----

    @Test
    void beforeFirstPoll_noAccountDetailsDelivered() {
        // No clock tick, so the scheduled poll has not run yet — a new subscriber sees nothing (there is no Loading state).
        List<AccountFetchResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(deliveries::add);

        assertThat(deliveries).isEmpty();
    }

    @Test
    void successfulPoll_publishesLoadedAccountDetails() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        List<AccountFetchResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(deliveries::add);
        clock.tick();

        assertThat(deliveries).contains(createLoadedResult(AGILE_TARIFF_A));
    }

    @Test
    void failedPoll_publishesFailedAccountDetails() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<AccountFetchResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(deliveries::add);
        clock.tick();

        assertThat(deliveries).last().isInstanceOfSatisfying(AccountFetchResult.Failed.class,
                                                             failed -> assertThat(failed.cause()).hasMessageContaining("octopus is down"));
    }

    @Test
    void consecutiveIdenticalFailures_publishOneFailedAccountDetail() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<AccountFetchResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(deliveries::add);
        clock.tick();

        // Next 12h poll: same outage, identical exception text → deduplicated, not re-published.
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(1);

        assertThat(deliveries).filteredOn(result -> result instanceof AccountFetchResult.Failed).hasSize(1);
    }

    @Test
    void failureAfterSuccess_publishesNothing() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"));

        List<AccountFetchResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(deliveries::add);
        clock.tick();

        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        advanceToPoll(1);

        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(2);

        assertThat(deliveries).satisfiesExactly(first -> assertThat(first).isInstanceOf(AccountFetchResult.Failed.class),
                                                second -> assertThat(second).isEqualTo(createLoadedResult(AGILE_TARIFF_A)));
    }

    @Test
    void failedAttemptBeforeALoad_publishesFailedAndARetrievalError() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"), completedFuture(account(AGILE_TARIFF_A)));

        Subscriptions subscriptions = startRetryingService();

        assertThat(subscriptions.accountResults()).satisfiesExactly(first -> assertThat(first).isInstanceOf(AccountFetchResult.Failed.class),
                                                                    second -> assertThat(second).isEqualTo(createLoadedResult(AGILE_TARIFF_A)));
        assertThat(subscriptions.priceResults()).singleElement().isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class);
    }

    @Test
    void failedAttemptAfterALoad_publishesNothing() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)),
                                                     failure("octopus is down"),
                                                     completedFuture(account(AGILE_TARIFF_A)));

        Subscriptions subscriptions = startRetryingService();
        advanceToPoll(1);

        assertThat(subscriptions.accountResults()).containsExactly(createLoadedResult(AGILE_TARIFF_A), createLoadedResult(AGILE_TARIFF_A));
        assertThat(subscriptions.priceResults()).isEmpty();
    }

    @Test
    void routingFailureOnTheFirstLoad_publishesARetrievalError_andTheNextPollResolvesTheTariff() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(tariffRegistry.forTariff(AGILE_TARIFF_A)).thenThrow(new IllegalStateException("registry is stopping"))
                                                      .thenReturn(createPricedSource(agileRegionA, TariffPricing.DYNAMIC, false));
        List<PriceResult> priceResults = new ArrayList<>();
        List<AccountFetchResult> accountResults = new ArrayList<>();
        service.start();
        service.subscribeToPrices(priceResults::add);
        service.subscribeToAccountDetails(accountResults::add);
        clock.tick();

        assertThat(priceResults).singleElement().isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                                                        error -> assertThat(error.cause()).hasRootCauseMessage("registry is stopping"));
        assertThat(accountResults).singleElement().isInstanceOf(AccountFetchResult.Failed.class);

        advanceToPoll(1);
        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(accountResults).last().isEqualTo(createLoadedResult(AGILE_TARIFF_A));
    }

    @Test
    void accountWithNoCurrentAgreement_keepsTheSupplyIdentifiersOutOfTheFailureAndTheLog() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, "2020-01-01T00:00:00Z", "2021-01-01T00:00:00Z"))));
        List<PriceResult> priceResults = new ArrayList<>();

        try (LogCapture log = LogCapture.capture(OctopusEnergyProviderService.class, Level.DEBUG)) {
            service.start();
            service.subscribeToPrices(priceResults::add);
            clock.tick();

            assertThat(priceResults).singleElement().isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class, error -> {
                assertThat(getCausalChain(error.cause())).extracting(Throwable::toString)
                                                         .allSatisfy(message -> assertThat(message).doesNotContain(MPAN, METER_SERIAL));
            });
            assertThat(log.lines().stream().flatMap(LogCapture.CapturedLine::text))
                    .isNotEmpty()
                    .allSatisfy(text -> assertThat(text).doesNotContain(MPAN, METER_SERIAL));
        }
    }

    @Test
    void pollRetry_retriesAFailedFetchButNotAnUnparseableResponse() {
        assertThat(OctopusEnergyProviderService.isRetryablePollFailure(new RuntimeException(new IOException("connection reset")))).isTrue();
        assertThat(OctopusEnergyProviderService.isRetryablePollFailure(
                new RuntimeException("Failed parsing response of 910 chars", new JsonParseException(null, "Unexpected character")))).isFalse();
    }

    @Test
    void successAfterAFailedRefresh_publishesLoaded() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        List<AccountFetchResult> accountResults = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(accountResults::add);
        clock.tick();

        when(accountService.getAccount()).thenReturn(failure("octopus is down"));
        advanceToPoll(1);
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        advanceToPoll(2);

        assertThat(accountResults).containsExactly(createLoadedResult(AGILE_TARIFF_A), createLoadedResult(AGILE_TARIFF_A));
    }

    @Test
    void accountDetails_redactTheMpanAndMeterSerials() {
        OctopusAccountDetails details = createAccountDetails(AGILE_TARIFF_A);

        assertThat(List.of(details.meterPoints().getFirst().toString(), new AccountFetchResult.Loaded(details).toString()))
                .allSatisfy(text -> assertThat(text).doesNotContain(MPAN, METER_SERIAL));
    }

    @Test
    void subscribeToAuthState_delegatesToAccountService() {
        Consumer<AuthState> consumer = _ -> {};
        Closeable subscription = () -> {};
        when(accountService.subscribeToAuthState(consumer)).thenReturn(subscription);

        service.start();
        Closeable result = service.subscribeToAuthState(consumer);

        assertThat(result).isSameAs(subscription);
        verify(accountService).subscribeToAuthState(consumer);
    }

    // ---- Lifecycle ----

    @Test
    void pollWhilePreviousFetchIsInFlight_isSkipped() {
        CompletableFuture<OctopusAccountData> firstFetch = new CompletableFuture<>();
        when(accountService.getAccount()).thenReturn(firstFetch, completedFuture(account(AGILE_TARIFF_A)));
        service.start();
        clock.tick();

        advanceToPoll(1);
        verify(accountService, times(1)).getAccount();

        firstFetch.complete(account(AGILE_TARIFF_A));
        clock.tick();
        advanceToPoll(2);
        verify(accountService, times(2)).getAccount();
    }

    @Test
    void resolutionStillRunning_isNotStartedAgain_andIsIgnoredOnceTheTariffHasLeftTheWindow() {
        CompletableFuture<TariffPriceSource> pendingResolution = new CompletableFuture<>();
        when(tariffRegistry.forTariff(FLEXIBLE_TARIFF_A)).thenReturn(pendingResolution);
        when(accountService.getAccount()).thenReturn(completedFuture(account(FLEXIBLE_TARIFF_A)),
                                                     completedFuture(account(FLEXIBLE_TARIFF_A)),
                                                     completedFuture(account(AGILE_TARIFF_A)));
        service.start();
        clock.tick();

        advanceToPoll(1);
        verify(tariffRegistry, times(1)).forTariff(FLEXIBLE_TARIFF_A);

        advanceToPoll(2);
        pendingResolution.complete(new TariffPriceSource.Priced(flexibleRegionA, TariffPricing.PUBLISHED, false));
        clock.tick();

        assertThat(flexibleRegionA.subscribers).isEmpty();
        assertThat(agileRegionA.subscribers).hasSize(1);
    }

    @Test
    void twoFailedResolutions_areRetriedTogether() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(FLEXIBLE_TARIFF_A, SWITCH, null))));
        when(tariffRegistry.forTariff(AGILE_TARIFF_A)).thenReturn(failedFuture(new RuntimeException("down")),
                                                                  createPricedSource(agileRegionA, TariffPricing.DYNAMIC, false));
        when(tariffRegistry.forTariff(FLEXIBLE_TARIFF_A)).thenReturn(failedFuture(new RuntimeException("down")),
                                                                     createPricedSource(flexibleRegionA, TariffPricing.PUBLISHED, false));
        service.start();
        clock.tick();

        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);

        verify(tariffRegistry, times(2)).forTariff(AGILE_TARIFF_A);
        verify(tariffRegistry, times(2)).forTariff(FLEXIBLE_TARIFF_A);
        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(flexibleRegionA.subscribers).hasSize(1);
    }

    @Test
    void updateFromATariffThatLeftTheWindow_isIgnored() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)), completedFuture(account(FLEXIBLE_TARIFF_A)));
        List<PriceResult> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToPrices(deliveries::add);
        clock.tick();
        Consumer<PriceResult> staleSubscriber = agileRegionA.subscribers.getFirst();
        advanceToPoll(1);
        deliveries.clear();

        staleSubscriber.accept(createFlatPrices(START, 4, 99.0));
        clock.tick();

        assertThat(deliveries).isEmpty();
    }

    @Test
    void currentPrices_waitForTheTimeline_whileALaterTariffIsUnresolved() {
        when(accountService.getAccount()).thenReturn(completedFuture(createAccountWithAgreements(
                createTariff(AGILE_TARIFF_A, Instant.parse("2023-06-01T00:00:00Z"), SWITCH),
                createTariff(FLEXIBLE_TARIFF_A, SWITCH, null))));
        when(tariffRegistry.forTariff(FLEXIBLE_TARIFF_A)).thenReturn(failedFuture(new RuntimeException("down")),
                                                                     createPricedSource(flexibleRegionA, TariffPricing.PUBLISHED, false));
        List<Object> deliveries = new ArrayList<>();
        service.start();
        service.subscribeToTariffTimeline(deliveries::add);
        service.subscribeToPrices(deliveries::add);
        clock.tick();

        agileRegionA.publish(createFlatPrices(START, SLOTS_TO_SWITCH, 12.0));
        clock.tick();
        assertThat(deliveries).describedAs("no timeline while the Flexible tariff is unresolved, so no prices either").isEmpty();

        clock.advanceTimeAndTick(OctopusPriceSlots.FAILURE_RETRY_DELAY);
        assertThat(deliveries).satisfiesExactly(first -> assertThat(first).isInstanceOf(TariffTimeline.class),
                                                second -> assertThat(second).isInstanceOf(Prices.class));
    }

    @Test
    void stop_closesTheSubscriptionsAndTheAccountOnTheExecutor() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        service.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        service.stop();
        assertThat(agileRegionA.subscribers).describedAs("the teardown waits for the executor").hasSize(1);
        clock.tick();

        assertThat(agileRegionA.subscribers).isEmpty();
        assertThat(agileRegionA.refreshTimeSubscribers).isEmpty();
        assertThat(forecastRegionA.subscribers).isEmpty();
        verify(accountService).close();
    }

    /// A stopped service makes no further call on Octopus with the user's API key: stopping cancels an account fetch that is still retrying, which ends its
    /// retries.
    @Test
    void stopWhileTheAccountFetchIsRetrying_cancelsTheFetch() {
        var retryingFetch = new CompletableFuture<OctopusAccountData>();
        service = createService(clock.createSingleThreadedSchedulingExecutor("provider"), new RetryableOperationExecutor() {
            // the service under test calls this only for its account fetch, whose T is OctopusAccountData
            @SuppressWarnings("unchecked")
            @Override
            public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                                Supplier<? extends CompletableFuture<T>> action,
                                                                BiConsumer<Long, Throwable> backoffEventConsumer) {
                return (CompletableFuture<T>) retryingFetch;
            }
        });
        service.start();
        clock.tick();

        service.stop();
        assertThat(retryingFetch).describedAs("the teardown waits for the executor").isNotDone();
        clock.tick();

        assertThat(retryingFetch).isCancelled();
    }

    // ---- Caching invariant: a second query over the same historical range is served from the cache, not re-fetched from Octopus ----

    @Test
    void queryRates_secondReadOfSameRange_doesNotRefetchFromConnector() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T00:30:00Z");   // two half-hour slots
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(octopusEnergy.region('A')).thenReturn(regionService);
        when(regionService.getStandardUnitRates(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_A), any(), any()))
                .thenReturn(completedFuture(List.of(rate("2024-01-01T00:00:00Z", "2024-01-01T00:30:00Z", 14.0),
                                                    rate("2024-01-01T00:30:00Z", "2024-01-01T01:00:00Z", 15.0))));
        service.start();

        readTwice(() -> service.queryRates("AGILE-23-12-06", AGILE_TARIFF_A, from, to));

        verify(regionService, times(1)).getStandardUnitRates(any(), any(), any(), any());
    }

    @Test
    void queryStandingCharges_secondReadOfSameRange_doesNotRefetchFromConnector() {
        Instant day = Instant.parse("2024-01-01T00:00:00Z");   // daily resolution: one day slot
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(octopusEnergy.region('A')).thenReturn(regionService);
        when(regionService.getStandingCharges(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_A), any(), any()))
                .thenReturn(completedFuture(List.of(standingCharge("2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z", 47.25))));
        service.start();

        readTwice(() -> service.queryStandingCharges("AGILE-23-12-06", AGILE_TARIFF_A, day, day));

        verify(regionService, times(1)).getStandingCharges(any(), any(), any(), any());
    }

    @Test
    void queryConsumption_secondReadOfSameRange_doesNotRefetchFromConnector() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T00:30:00Z");
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(accountService.getConsumption(eq(MPAN), eq(METER_SERIAL), any(), any()))
                .thenReturn(completedFuture(List.of(consumption("2024-01-01T00:00:00Z", "2024-01-01T00:30:00Z", 0.3),
                                                    consumption("2024-01-01T00:30:00Z", "2024-01-01T01:00:00Z", 0.4))));
        service.start();

        readTwice(() -> service.queryConsumption("user-1", MPAN, METER_SERIAL, from, to));

        verify(accountService, times(1)).getConsumption(any(), any(), any(), any());
    }

    @Test
    void queryConsumption_afterAnEarlierProviderOnTheSameCacheStopped_fetchesThroughThisProvidersAccount(@Mock OctopusAccountService earlierAccountService) {
        Instant earlierDay = Instant.parse("2023-12-31T00:00:00Z");
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T00:30:00Z");
        when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(earlierAccountService, accountService);
        when(earlierAccountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(earlierAccountService.getConsumption(eq(MPAN), eq(METER_SERIAL), any(), any())).thenReturn(completedFuture(List.of()));
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(accountService.getConsumption(eq(MPAN), eq(METER_SERIAL), any(), any()))
                .thenReturn(completedFuture(List.of(consumption("2024-01-01T00:00:00Z", "2024-01-01T00:30:00Z", 0.3))));
        // The earlier provider defines the user's consumption stream, then stops, as the provider of a restarted user app does.
        OctopusEnergyProviderService earlierService = createService(clock.createSingleThreadedSchedulingExecutor("earlier-provider"));
        earlierService.start();
        CompletableFuture<ImmutableMap<Instant, ConsumptionRow>> earlierRead = earlierService.queryConsumption("user-1",
                                                                                                               MPAN,
                                                                                                               METER_SERIAL,
                                                                                                               earlierDay,
                                                                                                               earlierDay);
        clock.tick();
        assertThat(earlierRead).succeedsWithin(Duration.ZERO);
        earlierService.stop();
        service.start();

        CompletableFuture<ImmutableMap<Instant, ConsumptionRow>> result = service.queryConsumption("user-1", MPAN, METER_SERIAL, from, to);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).satisfies(rows -> assertThat(rows).containsOnlyKeys(from));
        verify(earlierAccountService, times(1)).getConsumption(any(), any(), any(), any());
    }

    @Test
    void latestConsumptionInstant_returnsLatestPublishedSlot_viaDirectConnectorCall() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        when(accountService.getConsumption(eq(MPAN), eq(METER_SERIAL), any(), any()))
                .thenReturn(completedFuture(List.of(
                        consumption("2026-06-01T00:00:00Z", "2026-06-01T00:30:00Z", 0.3),
                        consumption("2026-06-02T11:30:00Z", "2026-06-02T12:00:00Z", 0.5))));
        service.start();

        CompletableFuture<Optional<Instant>> result = service.latestConsumptionInstant(MPAN, METER_SERIAL);
        clock.tick();

        assertThat(result).succeedsWithin(Duration.ZERO).satisfies(latest -> assertThat(latest).hasValue(Instant.parse("2026-06-02T11:30:00Z")));
        // Direct connector call — the frontier probe must NOT route through the (tombstoning) consumption cache stream.
        verify(accountService, times(1)).getConsumption(any(), any(), any(), any());
    }

    @Test
    void construction_refusesANonPositiveForwardWindow() {
        SchedulingExecutor serviceExecutor = clock.createSingleThreadedSchedulingExecutor("refused-provider");

        assertThatThrownBy(() -> createService(serviceExecutor, RetryableOperationExecutor.noRetries(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("forwardWindow must be positive: PT0S");
    }

    /// Moves the clock just past the `pollNumber`-th account poll after the first one, and runs what is due.
    private void advanceToPoll(int pollNumber) {
        clock.setTimeAndTick(START.plus(OctopusEnergyProviderService.DEFAULT_ACCOUNT_POLL_INTERVAL.multipliedBy(pollNumber)).plusSeconds(1));
    }

    private OctopusEnergyProviderService createService(SchedulingExecutor serviceExecutor) {
        return createService(serviceExecutor, RetryableOperationExecutor.noRetries());
    }

    private OctopusEnergyProviderService createService(SchedulingExecutor serviceExecutor, RetryableOperationExecutor retryExecutor) {
        return createService(serviceExecutor, retryExecutor, FORWARD_WINDOW);
    }

    private OctopusEnergyProviderService createService(SchedulingExecutor serviceExecutor,
                                                       RetryableOperationExecutor retryExecutor,
                                                       Duration forwardWindow) {
        return new OctopusEnergyProviderService(() -> serviceExecutor,
                                                clock,
                                                octopusEnergy,
                                                () -> octopusEnergy.account(ACCOUNT_ID, API_KEY),
                                                OctopusEnergyProviderService.DEFAULT_ACCOUNT_POLL_INTERVAL,
                                                forwardWindow,
                                                retryExecutor,
                                                tariffRegistry,
                                                priceForecastRegistry,
                                                cache,
                                                taskFailureRegistry);
    }

    /// Runs a cache-backed query twice over the same range, draining the provider's executor after each so the futures complete deterministically.
    private void readTwice(Supplier<CompletableFuture<?>> query) {
        CompletableFuture<?> first = query.get();
        clock.tick();
        assertThat(first).succeedsWithin(Duration.ZERO);
        CompletableFuture<?> second = query.get();
        clock.tick();
        assertThat(second).succeedsWithin(Duration.ZERO);
    }

    private static CompletableFuture<TariffPriceSource> createPricedSource(OctopusTariffPriceService service,
                                                                           TariffPricing pricing,
                                                                           boolean supplierManagedCharging) {
        return completedFuture(new TariffPriceSource.Priced(service, pricing, supplierManagedCharging));
    }

    /// @param to the period's exclusive end, `null` for an open-ended period
    private static TariffPeriod createPeriod(Instant from, @Nullable Instant to, TariffPricing pricing, boolean supplierManagedCharging) {
        return TariffPeriod.builder().setFrom(from).setTo(to).setPricing(pricing).setSupplierManagedCharging(supplierManagedCharging).build();
    }

    private static StandardUnitRate rate(String validFrom, String validTo, double valueIncVat) {
        return StandardUnitRate.builder()
                               .setValidFrom(Instant.parse(validFrom))
                               .setValidTo(Instant.parse(validTo))
                               .setValueExcVat(valueIncVat / 1.05)
                               .setValueIncVat(valueIncVat)
                               .build();
    }

    private static StandingCharge standingCharge(String validFrom, String validTo, double valueIncVat) {
        return StandingCharge.builder()
                             .setValidFrom(Instant.parse(validFrom))
                             .setValidTo(Instant.parse(validTo))
                             .setValueExcVat(valueIncVat / 1.05)
                             .setValueIncVat(valueIncVat)
                             .build();
    }

    private static ConsumptionRow consumption(String intervalStart, String intervalEnd, double kwh) {
        return ConsumptionRow.builder()
                             .setIntervalStart(Instant.parse(intervalStart))
                             .setIntervalEnd(Instant.parse(intervalEnd))
                             .setConsumption(kwh)
                             .build();
    }

    private static OctopusAccountData account(String tariffCode) {
        return createAccountWithAgreements(createTariffOverTheTestWindow(tariffCode));
    }

    private static OctopusAccountData accountWithExportThenImport(String exportTariffCode, String importTariffCode) {
        return OctopusAccountData.builder()
                                 .addProperties(AccountProperty.builder()
                                                               .addElectricityMeterPoints(meterPoint(true, createTariffOverTheTestWindow(exportTariffCode)))
                                                               .addElectricityMeterPoints(meterPoint(false, createTariffOverTheTestWindow(importTariffCode)))
                                                               .build())
                                 .build();
    }

    private static OctopusAccountData createAccountWithAgreements(Tariff... agreements) {
        return createAccountWithMeterPoints(meterPoint(false, agreements));
    }

    private static OctopusAccountData createAccountWithMeterPoints(ElectricityMeterPoint... meterPoints) {
        return OctopusAccountData.builder()
                                 .addProperties(AccountProperty.builder().addElectricityMeterPoints(meterPoints).build())
                                 .build();
    }

    /// @param validTo the agreement's exclusive end, `null` for an open-ended agreement with no end date
    private static Tariff createTariff(String tariffCode, String validFrom, @Nullable String validTo) {
        return createTariff(tariffCode, Instant.parse(validFrom), mapIfNotNull(validTo, Instant::parse));
    }

    private static ElectricityMeterPoint meterPoint(boolean isExport, Tariff... agreements) {
        return ElectricityMeterPoint.builder()
                                    .setMpan(MPAN)
                                    .addMeters(ElectricityMeter.builder().setSerialNumber(METER_SERIAL).build())
                                    .addTariffs(agreements)
                                    .setExport(isExport)
                                    .build();
    }

    /// Returns the [AccountFetchResult.Loaded] that a fetch of [#account(String)] publishes.
    private static AccountFetchResult createLoadedResult(String tariffCode) {
        return new AccountFetchResult.Loaded(createAccountDetails(tariffCode));
    }

    /// Returns the account details that [#account(String)] yields.
    private static OctopusAccountDetails createAccountDetails(String tariffCode) {
        return new OctopusAccountDetails(List.of(MeterPoint.builder()
                                                           .setMpan(MPAN)
                                                           .addMeterSerials(METER_SERIAL)
                                                           .addTariffs(createTariffOverTheTestWindow(tariffCode))
                                                           .setExport(false)
                                                           .build()));
    }

    private static Tariff createTariffOverTheTestWindow(String tariffCode) {
        return createTariff(tariffCode, TARIFF_VALID_FROM, TARIFF_VALID_TO);
    }

    /// @param validTo the agreement's exclusive end, `null` for an open-ended agreement with no end date
    private static Tariff createTariff(String tariffCode, Instant validFrom, @Nullable Instant validTo) {
        return Tariff.builder()
                     .setTariffCode(tariffCode)
                     .setValidFrom(validFrom)
                     .setValidTo(validTo)
                     .build();
    }

    private static Prices prices(String start, int idxOfPredictedPriceStart, Double... pricesPerSlot) {
        return new Prices(Instant.parse("2024-01-01T" + start + ":00Z"),
                          new PriceProfile(OctopusPriceSlots.SLOT_LENGTH_SEC, idxOfPredictedPriceStart, List.of(pricesPerSlot)));
    }

    /// Starts a service whose account poll retries once, subscribes to its account details and prices, and runs its first poll.
    private Subscriptions startRetryingService() {
        OctopusEnergyProviderService retryingService = createService(clock.createSingleThreadedSchedulingExecutor("retrying-provider"),
                                                                     new RetryingOnceExecutor());
        var subscriptions = new Subscriptions(new ArrayList<>(), new ArrayList<>());
        retryingService.start();
        retryingService.subscribeToAccountDetails(subscriptions.accountResults()::add);
        retryingService.subscribeToPrices(subscriptions.priceResults()::add);
        clock.tick();
        return subscriptions;
    }

    /// What a service started by [#startRetryingService()] has published to its subscribers.
    private record Subscriptions(List<AccountFetchResult> accountResults, List<PriceResult> priceResults) {
    }

    /// A [RetryableOperationExecutor] that retries a failed attempt once, passing the failure to `backoffEventConsumer` first, as a real executor does between
    /// attempts.
    private static final class RetryingOnceExecutor implements RetryableOperationExecutor {
        @Override
        public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                            Supplier<? extends CompletableFuture<T>> action,
                                                            BiConsumer<Long, Throwable> backoffEventConsumer) {
            return action.get().handle((result, throwable) -> {
                if (throwable == null) {
                    return completedFuture(result);
                }
                backoffEventConsumer.accept(0L, throwable);
                return action.get();
            }).thenCompose(future -> future);
        }
    }

    /// Hand-rolled fake of [OctopusTariffPriceService] that lets tests observe the subscriber list and publish results synchronously. A new subscriber
    /// receives the latest result, as from the real services.
    private static final class FakeTariffPriceService implements OctopusTariffPriceService {
        private final List<Consumer<PriceResult>> subscribers = new ArrayList<>();
        private final List<Consumer<Instant>> refreshTimeSubscribers = new ArrayList<>();
        private @Nullable PriceResult latestResult;

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public Optional<PriceResult> getPrices() {
            return Optional.ofNullable(latestResult);
        }

        @Override
        public Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
            subscribers.add(consumer);
            if (latestResult != null) {
                consumer.accept(latestResult);
            }
            return () -> subscribers.remove(consumer);
        }

        @Override
        public Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
            refreshTimeSubscribers.add(consumer);
            return () -> refreshTimeSubscribers.remove(consumer);
        }

        void publish(PriceResult result) {
            latestResult = result;
            for (var s : new ArrayList<>(subscribers)) {
                s.accept(result);
            }
        }

        void publishRefreshTime(Instant value) {
            for (var s : new ArrayList<>(refreshTimeSubscribers)) {
                s.accept(value);
            }
        }
    }

    /// Hand-rolled fake of [ForecastEnergyPriceService] that lets tests observe the subscriber list and publish forecasts synchronously.
    private static final class FakeForecastPriceService implements ForecastEnergyPriceService {
        private final List<Consumer<Prices>> subscribers = new ArrayList<>();

        @Override
        public Closeable subscribeToPrices(Consumer<Prices> consumer) {
            subscribers.add(consumer);
            return () -> subscribers.remove(consumer);
        }

        void publish(Prices prices) {
            for (var subscriber : new ArrayList<>(subscribers)) {
                subscriber.accept(prices);
            }
        }
    }
}
