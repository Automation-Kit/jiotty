package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.CompletableFutures;
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
import java.util.function.Consumer;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusAccountContextTest {

    private static final String ACCOUNT_ID = "A-AAAAAAAA";
    private static final String API_KEY = "sk_test_xxxxxxxxxxxx";

    private ProgrammableClock clock;
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;

    private OctopusAccountContext context;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2024-01-01T10:00:00Z"));
        var executor = clock.createSingleThreadedSchedulingExecutor("account-context");
        when(octopusEnergy.account(ACCOUNT_ID, API_KEY)).thenReturn(accountService);
        context = new OctopusAccountContext(() -> executor, octopusEnergy, ACCOUNT_ID, API_KEY, RetryableOperationExecutor.noRetries());
    }

    @Test
    void beforeFirstPollCompletes_valueIsLoading() {
        // No clock tick, so the scheduled poll has not run yet — a subscriber sees the initial Loading value.
        List<AccountFetchResult> received = new ArrayList<>();
        context.start();
        context.accountDetails().subscribe(received::add);

        assertThat(received).containsExactly(new AccountFetchResult.Loading());
    }

    @Test
    void successfulPoll_publishesLoaded() {
        OctopusAccountData account = account("E-1R-AGILE-23-12-06-A");
        when(accountService.getAccount()).thenReturn(completedFuture(account));

        List<AccountFetchResult> received = new ArrayList<>();
        context.start();
        context.accountDetails().subscribe(received::add);
        clock.tick();

        assertThat(received).contains(new AccountFetchResult.Loaded(account));
    }

    @Test
    void failedPoll_publishesFailed() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        context.start();
        context.accountDetails().subscribe(received::add);
        clock.tick();

        assertThat(received).last().isInstanceOfSatisfying(AccountFetchResult.Failed.class,
                                                           failed -> assertThat(failed.cause()).hasMessageContaining("octopus is down"));
    }

    @Test
    void consecutiveIdenticalFailures_publishOneFailed() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        context.start();
        context.accountDetails().subscribe(received::add);
        clock.tick();

        // Next 12h poll: same outage, identical exception text → deduplicated, not re-published.
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));
        clock.setTimeAndTick(Instant.parse("2024-01-01T22:00:01Z"));

        assertThat(received).filteredOn(result -> result instanceof AccountFetchResult.Failed).hasSize(1);
    }

    @Test
    void identicalFailureAfterSuccess_republishes() {
        when(accountService.getAccount()).thenReturn(CompletableFutures.failure("octopus is down"));

        List<AccountFetchResult> received = new ArrayList<>();
        context.start();
        context.accountDetails().subscribe(received::add);
        clock.tick();

        // Recovery resets the dedup state.
        when(accountService.getAccount()).thenReturn(completedFuture(account("E-1R-AGILE-23-12-06-A")));
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

        context.start();
        Closeable result = context.subscribeToAuthState(consumer);

        assertThat(result).isSameAs(subscription);
        verify(accountService).subscribeToAuthState(consumer);
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
}
