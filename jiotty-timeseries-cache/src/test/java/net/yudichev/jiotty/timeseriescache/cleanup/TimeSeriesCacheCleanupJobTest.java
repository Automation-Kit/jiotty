package net.yudichev.jiotty.timeseriescache.cleanup;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeSeriesCacheCleanupJobTest {
    private static final Instant T0 = Instant.parse("2026-04-15T10:00:00Z");
    private static final Duration INTERVAL = Duration.ofHours(24);
    private static final Duration RETENTION = Duration.ofDays(365L * 5);

    @Mock
    private TimeSeriesCache cache;
    @Mock
    private ActiveUserIdsSupplier activeUserIdsSupplier;

    private ProgrammableClock clock;
    private InMemoryVarStore varStore;
    private TimeSeriesCacheCleanupJob job;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTimeAndTick(T0);
        varStore = new InMemoryVarStore();
        lenient().when(cache.deleteOlderThan(any())).thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void tearDown() {
        if (job != null) {
            job.stop();
        }
    }

    @Test
    void firstStart_purgesOlderThanNowMinusRetention_andPersistsNextAt() {
        startJob(Optional.empty());
        clock.tick();

        verify(cache).deleteOlderThan(T0.minus(RETENTION));
        assertThat(varStore.readValue(TypeToken.of(Instant.class), TimeSeriesCacheCleanupJob.NEXT_AT_KEY)).contains(T0.plus(INTERVAL));
    }

    @Test
    void absentActiveUserIdsSupplier_stillPurges() {
        // The retention purge needs no active-user set; an absent supplier disables only orphan eviction and must not break the purge.
        startJob(Optional.empty());
        clock.tick();

        verify(cache).deleteOlderThan(T0.minus(RETENTION));
    }

    @Test
    void withActiveUserIdsSupplier_alsoQueriesItForOrphanEviction() {
        when(activeUserIdsSupplier.get()).thenReturn(CompletableFuture.completedFuture(Set.of("user-1")));

        startJob(Optional.of(activeUserIdsSupplier));
        clock.tick();

        verify(cache).deleteOlderThan(T0.minus(RETENTION));
        verify(activeUserIdsSupplier).get();
    }

    @Test
    void restart_withFutureNextAt_doesNotRunImmediately_runsAtNextAt() {
        Instant nextAt = T0.plus(Duration.ofHours(6));
        varStore.saveValue(TimeSeriesCacheCleanupJob.NEXT_AT_KEY, nextAt);

        startJob(Optional.empty());
        clock.tick();
        verify(cache, never()).deleteOlderThan(any());

        clock.setTimeAndTick(nextAt);
        verify(cache, times(1)).deleteOlderThan(nextAt.minus(RETENTION));
    }

    @Test
    void restart_withPastNextAt_runsImmediately() {
        varStore.saveValue(TimeSeriesCacheCleanupJob.NEXT_AT_KEY, T0.minus(Duration.ofHours(2)));

        startJob(Optional.empty());
        clock.tick();

        verify(cache).deleteOlderThan(T0.minus(RETENTION));
    }

    @Test
    void afterEachRun_reschedulesNextRunOneIntervalLater() {
        startJob(Optional.empty());
        clock.tick();
        verify(cache, times(1)).deleteOlderThan(T0.minus(RETENTION));

        clock.setTimeAndTick(T0.plus(INTERVAL));
        verify(cache, times(1)).deleteOlderThan(T0.plus(INTERVAL).minus(RETENTION));
        assertThat(varStore.readValue(TypeToken.of(Instant.class), TimeSeriesCacheCleanupJob.NEXT_AT_KEY))
                .contains(T0.plus(INTERVAL).plus(INTERVAL));
    }

    @Test
    void purgeFailure_doesNotPersistNextAt_butReschedulesNextRun() {
        when(cache.deleteOlderThan(any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DB unreachable")))
                .thenReturn(CompletableFuture.completedFuture(0));

        startJob(Optional.empty());
        clock.tick();
        assertThat(varStore.readValue(TypeToken.of(Instant.class), TimeSeriesCacheCleanupJob.NEXT_AT_KEY)).isEmpty();

        clock.setTimeAndTick(T0.plus(INTERVAL));
        verify(cache, times(2)).deleteOlderThan(any());
        assertThat(varStore.readValue(TypeToken.of(Instant.class), TimeSeriesCacheCleanupJob.NEXT_AT_KEY))
                .contains(T0.plus(INTERVAL).plus(INTERVAL));
    }

    private void startJob(Optional<ActiveUserIdsSupplier> supplier) {
        job = new TimeSeriesCacheCleanupJob(cache, supplier, varStore, clock, clock, INTERVAL, RETENTION);
        job.start();
    }
}
