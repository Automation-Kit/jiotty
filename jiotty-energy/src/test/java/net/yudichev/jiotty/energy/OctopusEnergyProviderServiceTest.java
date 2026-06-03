package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.security.AuthState;
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
import net.yudichev.jiotty.timeseriescache.InMemoryTimeSeriesCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
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
    private static final String GO_TARIFF_A = "E-1R-GO-VAR-22-10-14-A";
    private static final Instant TARIFF_VALID_FROM = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant TARIFF_VALID_TO = Instant.parse("2099-01-01T00:00:00Z");

    private ProgrammableClock clock;
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusRegionService regionService;
    @Mock
    private OctopusAgilePriceServiceRegistry octopusRegistry;
    @Mock
    private AgilePredictPriceServiceRegistry agilePredictRegistry;

    private FakeAgilePriceService agileRegionA;
    private FakeAgilePriceService agileRegionB;
    private FakeAgilePriceService agilePredictRegionA;
    private FakeAgilePriceService agilePredictRegionB;

    private OctopusEnergyProviderService service;
    private SchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        executor = clock.createSingleThreadedSchedulingExecutor("provider");

        agileRegionA = new FakeAgilePriceService();
        agileRegionB = new FakeAgilePriceService();
        agilePredictRegionA = new FakeAgilePriceService();
        agilePredictRegionB = new FakeAgilePriceService();

        lenient().when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(accountService);
        lenient().when(octopusRegistry.forTariff(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_A))).thenReturn(agileRegionA);
        lenient().when(octopusRegistry.forTariff(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_B))).thenReturn(agileRegionB);
        lenient().when(agilePredictRegistry.forRegion('A')).thenReturn(agilePredictRegionA);
        lenient().when(agilePredictRegistry.forRegion('B')).thenReturn(agilePredictRegionB);

        service = new OctopusEnergyProviderService(() -> executor, clock, octopusEnergy, ACCOUNT_ID, API_KEY,
                                                   RetryableOperationExecutor.noRetries(), RetryableOperationExecutor.noRetries(),
                                                   octopusRegistry, agilePredictRegistry, new InMemoryTimeSeriesCache());
    }

    // ---- Price routing ----

    @Test
    void initialAgileTariff_routesToCorrectDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();

        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(agilePredictRegionA.subscribers).hasSize(1);
        assertThat(agileRegionB.subscribers).isEmpty();

        // Real prices arrive — combine path notifies subscribers.
        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.subscribeToPrices(received::add);
        clock.tick();

        Prices realPrices = prices("10:00", 1, 5.0);
        agileRegionA.publish(Either.left(realPrices));
        clock.tick();

        assertThat(received).containsExactly(Either.left(realPrices));
    }

    @Test
    void tariffChange_reroutesOctopusDelegate() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        // Next 12h poll: user switched to tariff B (different region).
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_B)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(agileRegionA.subscribers).describedAs("old subscription closed").isEmpty();
        assertThat(agileRegionB.subscribers).describedAs("new subscription opened").hasSize(1);
        assertThat(agilePredictRegionA.subscribers).describedAs("old AgilePredict subscription closed").isEmpty();
        assertThat(agilePredictRegionB.subscribers).describedAs("new AgilePredict subscription opened").hasSize(1);
    }

    @Test
    void accountFetchFailure_emitsPriceRetrievalError() {
        // With RetryableOperationExecutor.noRetries() in setUp, a failed account fetch falls straight through to the whenComplete failure branch, which
        // emits PriceRetrievalError to subscribers via the dedup path.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.start();
        service.subscribeToPrices(received::add);
        clock.tick();

        assertThat(received).hasSize(1).first().satisfies(either -> assertThat(either.getRight()).hasValueSatisfying(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                        err -> assertThat(err.cause()).hasMessageContaining("octopus is down"))));
    }

    @Test
    void identicalFailureMessage_isDeduplicated() {
        // Same exception type + message on the second poll → dedup suppresses the second emission.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.start();
        service.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        // Next 12h poll: same outage continues, identical exception text.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).describedAs("dedup should suppress a second identical PriceRetrievalError").hasSize(1);
    }

    @Test
    void differentFailureMessage_isNotDeduplicated() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus 500"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.start();
        service.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("connection reset"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).describedAs("a different exception message should fire a fresh PriceRetrievalError").hasSize(2);
    }

    @Test
    void successAfterFailure_resetsDedup() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.start();
        service.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        // Recovery — successful account resolution, delegates wire up, dedup state resets.
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));
        received.clear();

        // Next outage — same exception message as the first. Should NOT be deduplicated because the success in between reset the dedup state.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-02T10:00:01Z"));

        assertThat(received).describedAs("after recovery, the same exception text should re-notify").hasSize(1);
    }

    @Test
    void unparseableAccountResponse_emitsPriceRetrievalErrorWithoutRetry() {
        // Empty account (no electricity meter points) → extractCurrentTariff throws; the service surfaces a PriceRetrievalError directly, no retry attempted.
        when(accountService.getAccount()).thenReturn(completedFuture(OctopusAccountData.builder().build()));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.start();
        service.subscribeToPrices(received::add);
        clock.tick();

        assertThat(received).hasSize(1).first().satisfies(either -> assertThat(either.getRight())
                .hasValueSatisfying(failure -> assertThat(failure).isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class)));
    }

    @Test
    void incompatibleTariff_emitsFailureAndClosesDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        service.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.subscribeToPrices(received::add);
        clock.tick();
        received.clear();

        // Next poll: user moved to non-Agile tariff.
        when(accountService.getAccount()).thenReturn(completedFuture(account(GO_TARIFF_A)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(agileRegionA.subscribers).describedAs("Octopus subscription closed on IncompatibleTariff").isEmpty();
        assertThat(agilePredictRegionA.subscribers).describedAs("AgilePredict subscription closed on IncompatibleTariff").isEmpty();
        assertThat(received).contains(Either.right(new EnergyPriceService.Failure.IncompatibleTariff(GO_TARIFF_A)));
    }

    // ---- Account details ----

    @Test
    void beforeFirstPoll_noAccountDetailsDelivered() {
        // No clock tick, so the scheduled poll has not run yet — a new subscriber sees nothing (there is no Loading state).
        List<AccountFetchResult> received = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(received::add);

        assertThat(received).isEmpty();
    }

    @Test
    void successfulPoll_publishesLoadedAccountDetails() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        List<AccountFetchResult> received = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(received::add);
        clock.tick();

        var expected = new OctopusAccountDetails(List.of(
                new OctopusAccountDetails.MeterPoint(MPAN, List.of(METER_SERIAL),
                                                     List.of(new OctopusAccountDetails.TariffPeriod(AGILE_TARIFF_A, TARIFF_VALID_FROM, TARIFF_VALID_TO)))));
        assertThat(received).contains(new AccountFetchResult.Loaded(expected));
    }

    @Test
    void failedPoll_publishesFailedAccountDetails() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(received::add);
        clock.tick();

        assertThat(received).last().isInstanceOfSatisfying(AccountFetchResult.Failed.class,
                                                           failed -> assertThat(failed.cause()).hasMessageContaining("octopus is down"));
    }

    @Test
    void consecutiveIdenticalFailures_publishOneFailedAccountDetail() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(received::add);
        clock.tick();

        // Next 12h poll: same outage, identical exception text → deduplicated, not re-published.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).filteredOn(result -> result instanceof AccountFetchResult.Failed).hasSize(1);
    }

    @Test
    void identicalFailureAfterSuccess_republishesAccountDetail() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        service.start();
        service.subscribeToAccountDetails(received::add);
        clock.tick();

        // Recovery resets the dedup state.
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        // Same exception text as the first failure — should re-publish because the success in between reset the dedup state.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-02T10:00:01Z"));

        assertThat(received).filteredOn(result -> result instanceof AccountFetchResult.Failed).hasSize(2);
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

    // ---- Query retry: a transient Octopus failure on an interactive query is retried; the classifier gates which failures qualify ----

    @Test
    void queryConsumption_transientFailure_isRetriedThenSucceeds() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");
        Instant to = Instant.parse("2024-01-01T00:30:00Z");
        // First attempt hits a transient gateway 502; the second succeeds.
        when(accountService.getConsumption(eq(MPAN), eq(METER_SERIAL), any(), any()))
                .thenReturn(CompletableFutures.failure(new HttpResponseException(502, "Bad Gateway")))
                .thenReturn(completedFuture(List.of(consumption("2024-01-01T00:00:00Z", "2024-01-01T00:30:00Z", 0.3))));
        OctopusEnergyProviderService retryingService = serviceWithQueryRetry(retryingOnce());
        retryingService.start();

        CompletableFuture<?> result = retryingService.queryConsumption("user-1", MPAN, METER_SERIAL, from, to);
        for (int i = 0; i < 5 && !result.isDone(); i++) {
            clock.tick();
        }

        result.join();   // would throw if the retry had not recovered the transient failure
        verify(accountService, times(2)).getConsumption(any(), any(), any(), any());
    }

    @Test
    void isTransientFailure_retriesGatewayAndNetworkErrors_butNotClientErrors() {
        assertThat(OctopusEnergyProviderService.isTransientFailure(new IOException("connection reset"))).isTrue();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(500, ""))).isTrue();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(502, "Bad Gateway"))).isTrue();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(503, ""))).isTrue();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(400, ""))).isFalse();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(401, ""))).isFalse();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new HttpResponseException(404, ""))).isFalse();
        assertThat(OctopusEnergyProviderService.isTransientFailure(new RuntimeException("bad argument"))).isFalse();
    }

    private OctopusEnergyProviderService serviceWithQueryRetry(RetryableOperationExecutor queryRetry) {
        return new OctopusEnergyProviderService(() -> executor, clock, octopusEnergy, ACCOUNT_ID, API_KEY,
                                                RetryableOperationExecutor.noRetries(), queryRetry,
                                                octopusRegistry, agilePredictRegistry, new InMemoryTimeSeriesCache());
    }

    /// Retries a failed action exactly once. The production query executor retries transient failures over a short window (jiotty-common's backoff); here we
    /// only need to prove the query path runs through the executor and that a retry re-invokes the action (isTransientFailure covers which failures qualify).
    private static RetryableOperationExecutor retryingOnce() {
        return new RetryableOperationExecutor() {
            @Override
            public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                                Supplier<? extends CompletableFuture<T>> action,
                                                                BiConsumer<Long, Throwable> backoffEventConsumer) {
                return action.get()
                             .<CompletableFuture<T>>handle((value, failure) -> failure == null ? completedFuture(value) : action.get())
                             .thenCompose(future -> future);
            }
        };
    }

    /// Runs a cache-backed query twice over the same range, draining the provider's executor after each so the futures complete deterministically.
    private void readTwice(Supplier<CompletableFuture<?>> query) {
        CompletableFuture<?> first = query.get();
        clock.tick();
        first.join();
        CompletableFuture<?> second = query.get();
        clock.tick();
        second.join();
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
        Tariff tariff = Tariff.builder()
                              .setTariffCode(tariffCode)
                              .setValidFrom(TARIFF_VALID_FROM)
                              .setValidTo(TARIFF_VALID_TO)
                              .build();
        ElectricityMeter meter = ElectricityMeter.builder().setSerialNumber(METER_SERIAL).build();
        ElectricityMeterPoint meterPoint = ElectricityMeterPoint.builder()
                                                                .setMpan(MPAN)
                                                                .addMeters(meter)
                                                                .addTariffs(tariff)
                                                                .build();
        return OctopusAccountData.builder()
                                 .addProperties(AccountProperty.builder().addElectricityMeterPoints(meterPoint).build())
                                 .build();
    }

    private static Prices prices(String start, int idxOfPredictedPriceStart, Double... pricesPerSlot) {
        return new Prices(Instant.parse("2024-01-01T" + start + ":00Z"),
                          new PriceProfile(1800, idxOfPredictedPriceStart, List.of(pricesPerSlot)));
    }

    /// Hand-rolled fake of [EnergyPriceService] that lets tests observe the subscriber list and publish results synchronously.
    private static final class FakeAgilePriceService implements EnergyPriceService {
        private final List<Consumer<Either<Prices, Failure>>> subscribers = new ArrayList<>();

        @Override
        public Optional<Either<Prices, Failure>> getPrices() {
            return Optional.empty();
        }

        @Override
        public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
            subscribers.add(consumer);
            return () -> subscribers.remove(consumer);
        }

        void publish(Either<Prices, Failure> result) {
            for (var s : new ArrayList<>(subscribers)) {
                s.accept(result);
            }
        }
    }
}
