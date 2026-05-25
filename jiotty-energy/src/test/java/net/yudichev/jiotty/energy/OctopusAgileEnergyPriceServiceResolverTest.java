package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusAgileEnergyPriceServiceResolverTest {

    private static final String ACCOUNT_ID = "A-AAAAAAAA";
    private static final String API_KEY = "sk_test_xxxxxxxxxxxx";
    private static final String AGILE_TARIFF_A = "E-1R-AGILE-23-12-06-A";
    private static final String AGILE_TARIFF_B = "E-1R-AGILE-23-12-06-B";
    private static final String GO_TARIFF_A = "E-1R-GO-VAR-22-10-14-A";

    private ProgrammableClock clock;
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusAgilePriceServiceRegistry octopusRegistry;
    @Mock
    private AgilePredictPriceServiceRegistry agilePredictRegistry;

    private FakeAgilePriceService agileRegionA;
    private FakeAgilePriceService agileRegionB;
    private FakeAgilePriceService agilePredictRegionA;
    private FakeAgilePriceService agilePredictRegionB;

    private OctopusAgileEnergyPriceServiceResolver resolver;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        var executor = clock.createSingleThreadedSchedulingExecutor("resolver");

        agileRegionA = new FakeAgilePriceService();
        agileRegionB = new FakeAgilePriceService();
        agilePredictRegionA = new FakeAgilePriceService();
        agilePredictRegionB = new FakeAgilePriceService();

        when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(accountService);
        lenient().when(octopusRegistry.forTariff(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_A))).thenReturn(agileRegionA);
        lenient().when(octopusRegistry.forTariff(eq("AGILE-23-12-06"), eq(AGILE_TARIFF_B))).thenReturn(agileRegionB);
        lenient().when(agilePredictRegistry.forRegion('A')).thenReturn(agilePredictRegionA);
        lenient().when(agilePredictRegistry.forRegion('B')).thenReturn(agilePredictRegionB);

        resolver = new OctopusAgileEnergyPriceServiceResolver(() -> executor, clock, octopusEnergy, ACCOUNT_ID, API_KEY, octopusRegistry, agilePredictRegistry,
                                                              RetryableOperationExecutor.noRetries());
    }

    @Test
    void initialAgileTariff_routesToCorrectDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        resolver.start();
        clock.tick();

        assertThat(agileRegionA.subscribers).hasSize(1);
        assertThat(agilePredictRegionA.subscribers).hasSize(1);
        assertThat(agileRegionB.subscribers).isEmpty();

        // Real prices arrive — combine path notifies subscribers.
        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.subscribeToPrices(received::add);
        clock.tick();

        Prices realPrices = prices("10:00", 1, 5.0);
        agileRegionA.publish(Either.left(realPrices));
        clock.tick();

        assertThat(received).containsExactly(Either.left(realPrices));
    }

    @Test
    void tariffChange_reroutesOctopusDelegate() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        resolver.start();
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
        // emits PriceRetrievalError to subscribers via the anti-spam path.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.start();
        resolver.subscribeToPrices(received::add);
        clock.tick();

        assertThat(received).hasSize(1).first().satisfies(either -> assertThat(either.getRight()).hasValueSatisfying(failure -> assertThat(failure)
                .isInstanceOfSatisfying(EnergyPriceService.Failure.PriceRetrievalError.class,
                                        err -> assertThat(err.cause()).hasMessageContaining("octopus is down"))));
    }

    @Test
    void identicalFailureMessage_isDeduplicated() {
        // Same exception type + message on the second poll → anti-spam suppresses the second emission.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.start();
        resolver.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        // Next 12h poll: same outage continues, identical exception text.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).describedAs("anti-spam dedup should suppress a second identical PriceRetrievalError").hasSize(1);
    }

    @Test
    void differentFailureMessage_isNotDeduplicated() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus 500"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.start();
        resolver.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("connection reset"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).describedAs("a different exception message should fire a fresh PriceRetrievalError").hasSize(2);
    }

    @Test
    void successAfterFailure_resetsAntiSpam() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.start();
        resolver.subscribeToPrices(received::add);
        clock.tick();
        assertThat(received).hasSize(1);

        // Recovery — successful account resolution, delegates wire up, anti-spam state resets.
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));
        received.clear();

        // Next outage — same exception message as the first. Should NOT be deduplicated because the success in between reset the anti-spam state.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-02T10:00:01Z"));

        assertThat(received).describedAs("after recovery, the same exception text should re-notify").hasSize(1);
    }

    @Test
    void unparseableAccountResponse_emitsPriceRetrievalErrorWithoutRetry() {
        // Empty account (no electricity meter points) → extractCurrentTariff throws; the resolver surfaces a PriceRetrievalError directly, no retry attempted.
        when(accountService.getAccount()).thenReturn(completedFuture(OctopusAccountData.builder().build()));

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.start();
        resolver.subscribeToPrices(received::add);
        clock.tick();

        assertThat(received).hasSize(1).first().satisfies(either -> assertThat(either.getRight())
                .hasValueSatisfying(failure -> assertThat(failure).isInstanceOf(EnergyPriceService.Failure.PriceRetrievalError.class)));
    }

    @Test
    void incompatibleTariff_emitsFailureAndClosesDelegates() {
        when(accountService.getAccount()).thenReturn(completedFuture(account(AGILE_TARIFF_A)));

        resolver.start();
        clock.tick();
        assertThat(agileRegionA.subscribers).hasSize(1);

        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        resolver.subscribeToPrices(received::add);
        clock.tick();
        received.clear();

        // Next poll: user moved to non-Agile tariff.
        when(accountService.getAccount()).thenReturn(completedFuture(account(GO_TARIFF_A)));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(agileRegionA.subscribers).describedAs("Octopus subscription closed on IncompatibleTariff").isEmpty();
        assertThat(agilePredictRegionA.subscribers).describedAs("AgilePredict subscription closed on IncompatibleTariff").isEmpty();
        assertThat(received).contains(Either.right(new EnergyPriceService.Failure.IncompatibleTariff(GO_TARIFF_A)));
    }

    private static OctopusAccountData account(String tariffCode) {
        Tariff tariff = Tariff.builder()
                              .setTariffCode(tariffCode)
                              .setValidFrom(Instant.parse("2020-01-01T00:00:00Z"))
                              .setValidTo(Instant.parse("2099-01-01T00:00:00Z"))
                              .build();
        return OctopusAccountData.builder()
                                 .addProperties(AccountProperty.builder()
                                                               .addElectricityMeterPoints(ElectricityMeterPoint.builder()
                                                                                                               .setMpan("9999999999999")
                                                                                                               .addTariffs(tariff)
                                                                                                               .build())
                                                               .build())
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
        public Optional<Either<Prices, Failure>> getResult() {
            return Optional.empty();
        }

        @Override
        public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
            subscribers.add(consumer);
            return () -> subscribers.remove(consumer);
        }

        @Override
        public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
            throw new UnsupportedOperationException();
        }

        void publish(Either<Prices, Failure> result) {
            for (var s : new ArrayList<>(subscribers)) {
                s.accept(result);
            }
        }
    }
}
