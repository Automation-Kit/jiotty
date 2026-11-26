package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.nextUkTimeOfDayAfter;

/// A [DailyRetrievalNode] that wakes at each daily retrieval, where its value changes.
final class DailyRetrievalNodeImpl extends BaseServerNode implements DailyRetrievalNode {
    private final LocalTime dailyRetrievalTime;

    private @Nullable Instant nextDailyRetrieval;
    /// The wake-up at [#nextDailyRetrieval()]; `null` before the first wave, and from the wake-up's firing until the wave that the wake-up triggers.
    private @Nullable Closeable wakeUp;

    /// @param dailyRetrievalTime the UK clock time of the daily retrieval
    public DailyRetrievalNodeImpl(GraphRunner runner, LocalTime dailyRetrievalTime) {
        super(runner);
        this.dailyRetrievalTime = checkNotNull(dailyRetrievalTime);
    }

    @Override
    public @Nullable Instant nextDailyRetrieval() {
        return nextDailyRetrieval;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: nextDailyRetrieval={}, wakeUpArmed={}", when, nextDailyRetrieval, wakeUp != null);
    }

    /// Arms the wake-up on every wave, whether or not [#nextDailyRetrieval()] changes. An executor may run the wake-up a little before the daily retrieval,
    /// and the wave that the early wake-up triggers then arms a wake-up for the same daily retrieval.
    @Override
    protected boolean doWave() {
        Instant now = waveTime();
        Instant newNextDailyRetrieval = nextUkTimeOfDayAfter(now, dailyRetrievalTime);
        boolean changed = !newNextDailyRetrieval.equals(nextDailyRetrieval);
        nextDailyRetrieval = newNextDailyRetrieval;
        closeIfNotNull(wakeUp);
        wakeUp = scheduleAndCancelOnClose(Duration.between(now, newNextDailyRetrieval), () -> {
            wakeUp = null;
            triggerMeAndParentsInNewWave("daily retrieval due");
        });
        return changed;
    }
}
