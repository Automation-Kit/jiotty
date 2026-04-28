package net.yudichev.jiotty.adminalerts.cleanup;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertHistoryCleanupJobTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofHours(24);
    private static final Duration RETENTION = Duration.ofDays(180);

    @Mock
    private AdminAlertService alertService;

    private ProgrammableClock clock;
    private InMemoryVarStore varStore;
    private AlertHistoryCleanupJob job;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTimeAndTick(T0);
        varStore = new InMemoryVarStore();
        lenient().when(alertService.deleteResolvedOlderThan(any())).thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void tearDown() {
        if (job != null) {
            job.stop();
        }
    }

    @Test
    void firstStart_runsCleanupImmediatelyAndPersistsNextAt() {
        startJob();
        clock.tick();

        verify(alertService).deleteResolvedOlderThan(RETENTION);
        assertThat(varStore.readValue(TypeToken.of(Instant.class), AlertHistoryCleanupJob.NEXT_AT_KEY)).contains(T0.plus(INTERVAL));
    }

    @Test
    void restart_withFutureNextAt_doesNotRunImmediately_runsAtNextAt() {
        Instant nextAt = T0.plus(Duration.ofHours(6));
        varStore.saveValue(AlertHistoryCleanupJob.NEXT_AT_KEY, nextAt);

        startJob();
        clock.tick();
        verify(alertService, never()).deleteResolvedOlderThan(any());

        clock.setTimeAndTick(nextAt);
        verify(alertService, times(1)).deleteResolvedOlderThan(RETENTION);
    }

    @Test
    void restart_withPastNextAt_runsImmediately() {
        varStore.saveValue(AlertHistoryCleanupJob.NEXT_AT_KEY, T0.minus(Duration.ofHours(2)));

        startJob();
        clock.tick();

        verify(alertService).deleteResolvedOlderThan(RETENTION);
    }

    @Test
    void afterEachRun_reschedulesNextRunOneIntervalLater() {
        startJob();
        clock.tick();
        verify(alertService, times(1)).deleteResolvedOlderThan(RETENTION);

        clock.setTimeAndTick(T0.plus(INTERVAL));
        verify(alertService, times(2)).deleteResolvedOlderThan(RETENTION);
        assertThat(varStore.readValue(TypeToken.of(Instant.class), AlertHistoryCleanupJob.NEXT_AT_KEY))
                .contains(T0.plus(INTERVAL).plus(INTERVAL));

        clock.setTimeAndTick(T0.plus(INTERVAL).plus(INTERVAL));
        verify(alertService, times(3)).deleteResolvedOlderThan(RETENTION);
    }

    @Test
    void cleanupFailure_doesNotPersistNextAt_butReschedulesNextRun() {
        when(alertService.deleteResolvedOlderThan(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DB unreachable")))
                .thenReturn(CompletableFuture.completedFuture(0));

        startJob();
        clock.tick();
        assertThat(varStore.readValue(TypeToken.of(Instant.class), AlertHistoryCleanupJob.NEXT_AT_KEY)).isEmpty();

        clock.setTimeAndTick(T0.plus(INTERVAL));
        verify(alertService, times(2)).deleteResolvedOlderThan(RETENTION);
        assertThat(varStore.readValue(TypeToken.of(Instant.class), AlertHistoryCleanupJob.NEXT_AT_KEY))
                .contains(T0.plus(INTERVAL).plus(INTERVAL));
    }

    private void startJob() {
        job = new AlertHistoryCleanupJob(alertService, varStore, clock, clock, INTERVAL, RETENTION);
        job.start();
    }
}
