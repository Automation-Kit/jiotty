package net.yudichev.jiotty.energy.octopus;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.ForecastPrice;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSource;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.energy.octopus.ForecastEnergyPriceServiceImpl.MAX_PERSISTED_FORECAST_AGE;
import static net.yudichev.jiotty.energy.octopus.ForecastEnergyPriceServiceImpl.RETRIEVAL_PERIOD;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.PERIOD_SEC;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.TYPICAL_SLOT_COUNT;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.slots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ForecastEnergyPriceServiceImplTest {

    private static final Instant START_TIME = Instant.parse("2024-01-01T06:25:00Z");
    private static final String STORE_KEY = "priceForecast.lastGood.A";
    /// How far ahead a fixture forecast reaches from its first slot.
    private static final Duration FIXTURE_COVERAGE = Duration.ofSeconds((long) PERIOD_SEC * TYPICAL_SLOT_COUNT);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final InMemoryVarStore varStore = new InMemoryVarStore();
    /// Everything the service published, in order, as seen by a subscriber registered right after start.
    private final List<Prices> published = new ArrayList<>();

    @Mock
    private UpstreamHealthHandler statusHandler;
    private ProgrammableClock clock;
    private SchedulingExecutor executor;
    private FakeSource source1;
    private FakeSource source2;
    private FakeSource source3;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(START_TIME);
        executor = clock.createSingleThreadedSchedulingExecutor("executor");
        source1 = new FakeSource("s1");
        source2 = new FakeSource("s2");
        source3 = new FakeSource("s3");
    }

    @Test
    void primarySourceServes_publishesAndPersists() {
        source1.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();

        assertThat(published).containsExactly(prices(START_TIME));
        verify(statusHandler).onSuccess();
        verify(statusHandler, never()).onFailure(any(), any());
        assertThat(source2.timesCalled).isZero();
        assertThat(source3.timesCalled).isZero();
        assertThat(attemptCount("s1", "success")).isEqualTo(1.0);
        assertThat(attemptCount("s1", "fetch_error")).isZero();
        assertThat(varStore.readValue(StoredForecast.class, STORE_KEY)).hasValue(storedForecast(START_TIME, START_TIME));
    }

    @Test
    void lateSubscriberReceivesTheForecastPublishedBeforeItSubscribed() {
        source1.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));
        var service = create();
        clock.tick();

        List<Prices> lateSubscriberPrices = new ArrayList<>();
        service.subscribeToPrices(lateSubscriberPrices::add);
        clock.tick();

        assertThat(lateSubscriberPrices).containsExactly(prices(START_TIME));
    }

    @Test
    void closingASubscriptionStopsDeliveries() {
        source1.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));
        Instant secondRefreshTime = START_TIME.plus(RETRIEVAL_PERIOD);
        source1.respondWith(completedFuture(slots(secondRefreshTime, TYPICAL_SLOT_COUNT)));
        var service = create();

        List<Prices> cancelledSubscriberPrices = new ArrayList<>();
        var subscription = service.subscribeToPrices(cancelledSubscriberPrices::add);
        clock.tick();
        subscription.close();
        clock.advanceTimeAndTick(RETRIEVAL_PERIOD);

        assertThat(cancelledSubscriberPrices).containsExactly(prices(START_TIME));
        assertThat(published).containsExactly(prices(START_TIME), prices(secondRefreshTime));
    }

    @Test
    void primarySourceFails_nextSourceServes() {
        source1.respondWith(failedFuture(new RuntimeException("boom")));
        source2.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();

        assertThat(published).containsExactly(prices(START_TIME));
        verify(statusHandler).onSuccess();
        verify(statusHandler, never()).onFailure(any(), any());
        assertThat(source3.timesCalled).isZero();
        assertThat(attemptCount("s1", "fetch_error")).isEqualTo(1.0);
        assertThat(attemptCount("s2", "success")).isEqualTo(1.0);
    }

    @Test
    void invalidPayloadFallsThroughToNextSource() {
        source1.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT).subList(0, 4)));
        source2.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();

        assertThat(published).containsExactly(prices(START_TIME));
        assertThat(attemptCount("s1", "invalid_payload")).isEqualTo(1.0);
        assertThat(attemptCount("s2", "success")).isEqualTo(1.0);
    }

    @Test
    void allSourcesFail_reportsFailureNamingEachSourceOutcome() {
        source1.respondWith(failedFuture(new RuntimeException("boom")));
        source2.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT).subList(0, 4)));
        source3.respondWith(failedFuture(new RuntimeException("boom 3")));

        create();
        clock.tick();

        assertThat(published).isEmpty();
        verify(statusHandler).onFailure(contains("region A"),
                                        argThat(cause -> cause.getMessage().contains("s1=fetch_error")
                                                         && cause.getMessage().contains("s2=invalid_payload")
                                                         && cause.getMessage().contains("s3=fetch_error")));
        verify(statusHandler, never()).onSuccess();
    }

    @Test
    void recoveryAfterFailedSweep_publishesAndReportsSuccess() {
        respondWithFailureFromAllSources();
        Instant secondRefreshTime = START_TIME.plus(RETRIEVAL_PERIOD);
        source1.respondWith(completedFuture(slots(secondRefreshTime, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();
        clock.advanceTimeAndTick(RETRIEVAL_PERIOD);

        assertThat(published).containsExactly(prices(secondRefreshTime));
        verify(statusHandler).onSuccess();
    }

    @Test
    void publishesPersistedForecastOnStartUntilFirstSweepSucceeds() {
        Instant persistedStart = START_TIME.minus(Duration.ofHours(2));
        varStore.saveValue(STORE_KEY, storedForecast(persistedStart, persistedStart));
        respondWithFailureFromAllSources();

        create();
        clock.tick();

        assertThat(published).containsExactly(prices(persistedStart));
    }

    @Test
    void ignoresPersistedForecastOlderThanMaxAge() {
        Instant savedAt = START_TIME.minus(MAX_PERSISTED_FORECAST_AGE).minusSeconds(60);
        varStore.saveValue(STORE_KEY, storedForecast(savedAt, savedAt));
        respondWithFailureFromAllSources();

        create();
        clock.tick();

        assertThat(published).isEmpty();
    }

    @Test
    void ignoresPersistedForecastEndingInThePast() {
        // started far enough back that the whole profile, saved recently enough to still be trusted, has already been consumed
        Instant profileStart = START_TIME.minus(FIXTURE_COVERAGE).minus(Duration.ofHours(1));
        varStore.saveValue(STORE_KEY, storedForecast(START_TIME.minus(Duration.ofHours(1)), profileStart));
        respondWithFailureFromAllSources();

        create();
        clock.tick();

        assertThat(published).isEmpty();
    }

    @Test
    void cancelsOverrunningSweepOnNextRefresh() {
        source1.respondWith(new CompletableFuture<>());
        Instant secondRefreshTime = START_TIME.plus(RETRIEVAL_PERIOD);
        source1.respondWith(completedFuture(slots(secondRefreshTime, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();
        clock.advanceTimeAndTick(RETRIEVAL_PERIOD);

        assertThat(published).containsExactly(prices(secondRefreshTime));
        assertThat(source1.timesCalled).isEqualTo(2);
        verify(statusHandler, never()).onFailure(any(), any());
    }

    @Test
    void sourceCompletingAfterNextRefresh_doesNotContinueTheStaleSweep() {
        var firstFetch = new CompletableFuture<List<ForecastPrice>>();
        source1.respondWith(firstFetch);
        Instant secondRefreshTime = START_TIME.plus(RETRIEVAL_PERIOD);
        source1.respondWith(completedFuture(slots(secondRefreshTime, TYPICAL_SLOT_COUNT)));

        create();
        clock.tick();
        clock.advanceTimeAndTick(RETRIEVAL_PERIOD);
        firstFetch.completeExceptionally(new RuntimeException("late failure"));
        clock.tick();

        assertThat(source2.timesCalled).isZero();
        assertThat(attemptCount("s1", "fetch_error")).isZero();
        assertThat(published).containsExactly(prices(secondRefreshTime));
    }

    @Test
    void stopWithSweepInFlight_doesNotReportFailure() {
        source1.respondWith(new CompletableFuture<>());
        var service = create();
        clock.tick();

        service.stop();
        clock.tick();

        verify(statusHandler, never()).onFailure(any(), any());
    }

    @Test
    void worksWithoutVarStore() {
        source1.respondWith(completedFuture(slots(START_TIME, TYPICAL_SLOT_COUNT)));

        create(Optional.empty());
        clock.tick();

        assertThat(published).containsExactly(prices(START_TIME));
    }

    private ForecastEnergyPriceServiceImpl create() {
        return create(Optional.of(varStore));
    }

    private ForecastEnergyPriceServiceImpl create(Optional<VarStore> varStore) {
        var service = new ForecastEnergyPriceServiceImpl(() -> executor,
                                                         List.of(source1, source2, source3),
                                                         RetryableOperationExecutor.noRetries(),
                                                         statusHandler,
                                                         meterRegistry,
                                                         varStore,
                                                         clock,
                                                         'A');
        service.start();
        service.subscribeToPrices(published::add);
        return service;
    }

    private void respondWithFailureFromAllSources() {
        source1.respondWith(failedFuture(new RuntimeException("boom")));
        source2.respondWith(failedFuture(new RuntimeException("boom")));
        source3.respondWith(failedFuture(new RuntimeException("boom")));
    }

    private double attemptCount(String sourceName, String outcome) {
        return meterRegistry.get("price_forecast_attempts_total").tags("source", sourceName, "outcome", outcome).counter().count();
    }

    private static StoredForecast storedForecast(Instant savedAt, Instant profileStart) {
        return StoredForecast.builder()
                             .setSavedAt(savedAt)
                             .setSource("s1")
                             .setProfileStart(profileStart)
                             .setIntervalLengthSec(PERIOD_SEC)
                             .setPrices(prices(profileStart).profile().pricePerInterval())
                             .build();
    }

    private static Prices prices(Instant start) {
        return ForecastFixtures.prices(start, TYPICAL_SLOT_COUNT);
    }

    private static final class FakeSource implements PriceForecastSource {
        private final String name;
        private final Deque<CompletableFuture<List<ForecastPrice>>> responses = new ArrayDeque<>();
        private int timesCalled;

        FakeSource(String name) {
            this.name = name;
        }

        void respondWith(CompletableFuture<List<ForecastPrice>> response) {
            responses.add(response);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public CompletableFuture<List<ForecastPrice>> getPrices(String region, int dayCount) {
            timesCalled++;
            return checkNotNull(responses.poll(), "%s has no response for call %s", name, timesCalled);
        }
    }
}
