package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeter;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.TestGraph;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountNodeImplTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofHours(12);
    private static final String MPAN = "9999999999999";
    private static final String METER_SERIAL = "99XXX99999";
    private static final OctopusAccountData ACCOUNT = createAccount();

    private final TestGraph graph = TestGraph.startingAt(START);
    private final FetchHistory fetchHistory = new FetchHistory();
    @Mock
    private OctopusAccountService accountService;
    private AccountNodeImpl node;

    @AfterEach
    void tearDown() {
        graph.assertNoWaveFailed();
    }

    @Test
    void firstPoll_loadsTheAccount_andEachPollLoadsAnotherInstance() {
        when(accountService.getAccount()).thenReturn(completedFuture(ACCOUNT));
        registerNode(Optional.empty());
        AccountState firstState = node.state();
        assertThat(firstState).isInstanceOfSatisfying(Loaded.class, loaded -> assertThat(loaded.account()).isSameAs(ACCOUNT));

        graph.clock().advanceTimeAndTick(POLL_INTERVAL);

        assertThat(node.state()).isInstanceOf(Loaded.class).isNotSameAs(firstState);
    }

    @Test
    void identicalFailures_keepTheFirstUnavailableState_andADifferentOneReplacesIt() {
        when(accountService.getAccount()).thenReturn(failure("octopus is down"), failure("octopus is down"), failure("octopus is on fire"));
        registerNode(Optional.empty());
        AccountState firstFailure = node.state();
        assertThat(firstFailure).isInstanceOf(Unavailable.class);

        graph.clock().advanceTimeAndTick(POLL_INTERVAL);
        assertThat(node.state()).isSameAs(firstFailure);

        graph.clock().advanceTimeAndTick(POLL_INTERVAL);
        assertThat(node.state()).isInstanceOfSatisfying(Unavailable.class, unavailable -> assertThat(unavailable.cause()).hasMessage("octopus is on fire"));
    }

    @Test
    void failureAfterALoad_leavesTheStateAlone() {
        when(accountService.getAccount()).thenReturn(completedFuture(ACCOUNT), failure("octopus is down"));
        registerNode(Optional.empty());
        AccountState loadedState = node.state();

        graph.clock().advanceTimeAndTick(POLL_INTERVAL);

        assertThat(node.state()).isSameAs(loadedState);
    }

    @Test
    void pollWhileTheFetchIsRunning_isSkipped() {
        when(accountService.getAccount()).thenReturn(new CompletableFuture<>());
        registerNode(Optional.empty());

        graph.clock().advanceTimeAndTick(POLL_INTERVAL);

        verify(accountService, times(1)).getAccount();
    }

    @Test
    void seededLoadedState_isKept_andTheFirstPollWaitsForThePollIntervalAfterTheLatestFetch() {
        when(accountService.getAccount()).thenReturn(completedFuture(ACCOUNT));
        Loaded seed = new Loaded(ACCOUNT);
        fetchHistory.recordStart(START.minus(POLL_INTERVAL.dividedBy(2)));
        registerNode(Optional.of(seed));
        assertThat(node.state()).isSameAs(seed);

        graph.clock().setTimeAndTick(START.plus(POLL_INTERVAL.dividedBy(2)).minusNanos(1));
        verify(accountService, never()).getAccount();

        graph.clock().setTimeAndTick(START.plus(POLL_INTERVAL.dividedBy(2)));
        verify(accountService, times(1)).getAccount();
    }

    @Test
    void withoutALoadedState_theFirstPollWaitsForTheRetryDelayAfterTheLatestFetch() {
        when(accountService.getAccount()).thenReturn(completedFuture(ACCOUNT));
        fetchHistory.recordStart(START);
        registerNode(Optional.empty());
        verify(accountService, never()).getAccount();

        graph.clock().setTimeAndTick(START.plus(FAILURE_RETRY_DELAY));

        verify(accountService, times(1)).getAccount();
    }

    @Test
    void closing_cancelsTheRunningFetch_andDropsItsEnd() {
        var runningFetch = new CompletableFuture<OctopusAccountData>();
        when(accountService.getAccount()).thenReturn(runningFetch);
        registerNode(Optional.empty());

        graph.graph().close();
        graph.clock().tick();

        assertThat(runningFetch).isCancelled();
        assertThat(node.state()).isNull();
    }

    @Test
    void logState_keepsTheSupplyIdentifiersOutOfTheLog() {
        when(accountService.getAccount()).thenReturn(completedFuture(ACCOUNT));

        try (LogCapture log = LogCapture.capture(AccountNodeImpl.class, Level.DEBUG)) {
            registerNode(Optional.empty());

            assertThat(log.lines().stream().flatMap(LogCapture.CapturedLine::text))
                    .isNotEmpty()
                    .allSatisfy(text -> assertThat(text).doesNotContain(MPAN, METER_SERIAL));
        }
    }

    private void registerNode(Optional<AccountState> state) {
        node = new AccountNodeImpl(RetryableOperationExecutor.noRetries(), POLL_INTERVAL, graph.runner(), accountService, fetchHistory, state);
        node.registerInGraph();
        graph.runWave();
    }

    private static OctopusAccountData createAccount() {
        Tariff agreement = Tariff.builder().setTariffCode("E-1R-AGILE-23-12-06-A").setValidFrom(START).build();
        ElectricityMeterPoint meterPoint = ElectricityMeterPoint.builder()
                                                                .setMpan(MPAN)
                                                                .addMeters(ElectricityMeter.builder().setSerialNumber(METER_SERIAL).build())
                                                                .addTariffs(agreement)
                                                                .setExport(false)
                                                                .build();
        return OctopusAccountData.builder().addProperties(AccountProperty.builder().addElectricityMeterPoints(meterPoint).build()).build();
    }
}
