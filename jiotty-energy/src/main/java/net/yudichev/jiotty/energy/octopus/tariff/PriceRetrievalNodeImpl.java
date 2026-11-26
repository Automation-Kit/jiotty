package net.yudichev.jiotty.energy.octopus.tariff;

import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;

/// A [PriceRetrievalNode] that calls a [TariffRetrieval] to fetch the prices and to plan what follows each retrieval. A retrieval that ends is stored until
/// the next wave, which plans what follows it. The first retrieval starts at the earliest [OctopusPriceSlots#FAILURE_RETRY_DELAY] after the latest one that
/// the [FetchHistory] records, so however often the graph panics, the rebuilt graphs call Octopus at most once per that delay.
final class PriceRetrievalNodeImpl extends BaseServerNode implements PriceRetrievalNode {
    private final TariffRetrieval retrieval;
    private final DailyRetrievalNode dailyRetrievalNode;
    private final FetchHistory fetchHistory;

    /// The latest retrieval that has ended since the last wave; `null` when none has.
    private @Nullable RetrievalOutcome pendingOutcome;
    /// The attempt that fell due since the last wave; `null` when none has.
    private @Nullable RetrievalAttempt pendingAttempt;
    /// The [DailyRetrievalNode#nextDailyRetrieval()] of the last wave; `null` before the first wave.
    private @Nullable Instant nextDailyRetrieval;
    private @Nullable RetrievalOutcome outcome;
    private @Nullable RetrievalPlan plan;
    /// The timer of the attempt that is armed; `null` while none is.
    private @Nullable Closeable attemptTimer;

    public PriceRetrievalNodeImpl(GraphRunner runner, TariffRetrieval retrieval, DailyRetrievalNode dailyRetrievalNode, FetchHistory fetchHistory) {
        super(runner);
        this.retrieval = checkNotNull(retrieval);
        this.dailyRetrievalNode = subscribeTo(checkNotNull(dailyRetrievalNode));
        this.fetchHistory = checkNotNull(fetchHistory);
    }

    @Override
    public @Nullable RetrievalOutcome outcome() {
        return outcome;
    }

    @Override
    public @Nullable RetrievalPlan plan() {
        return plan;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: pendingOutcome={}, pendingAttempt={}, nextDailyRetrieval={}, outcome={}, plan={}, attemptTimerArmed={}",
                     when, pendingOutcome, pendingAttempt, nextDailyRetrieval, outcome, plan, attemptTimer != null);
    }

    @Override
    protected boolean doWave() {
        Instant newNextDailyRetrieval = dailyRetrievalNode.nextDailyRetrieval();
        if (newNextDailyRetrieval == null) {
            return false;
        }
        Instant now = waveTime();
        Instant previousNextDailyRetrieval = nextDailyRetrieval;
        nextDailyRetrieval = newNextDailyRetrieval;
        boolean changed = applyPendingOutcome(now);
        RetrievalAttempt pendingAttempt = this.pendingAttempt;
        this.pendingAttempt = null;
        if (previousNextDailyRetrieval == null) {
            startFirstRetrieval(now);
        } else if (!previousNextDailyRetrieval.equals(newNextDailyRetrieval)) {
            // The daily retrieval supersedes the attempt, whether it is armed or has fallen due in this wave.
            cancelAttempt();
            startRetrieval(now, now);
        } else if (pendingAttempt != null) {
            startRetrieval(pendingAttempt.from().orElse(now), now);
        }
        return changed;
    }

    /// Stores the retrieval that has ended since the last wave as [#outcome()], and arms the attempt that its plan calls for in place of the armed one.
    ///
    /// @return whether a retrieval has ended
    private boolean applyPendingOutcome(Instant now) {
        RetrievalOutcome pendingOutcome = this.pendingOutcome;
        if (pendingOutcome == null) {
            return false;
        }
        this.pendingOutcome = null;
        outcome = pendingOutcome;
        RetrievalPlan newPlan = retrieval.plan(pendingOutcome, now);
        plan = newPlan;
        cancelAttempt();
        newPlan.attempt().ifPresent(attempt -> armAttempt(attempt, now));
        return true;
    }

    private void startFirstRetrieval(Instant now) {
        Instant start = fetchHistory.earliestNextStart(now, FAILURE_RETRY_DELAY);
        if (start.isAfter(now)) {
            armAttempt(RetrievalAttempt.builder().setAt(start).build(), now);
        } else {
            startRetrieval(now, now);
        }
    }

    private void armAttempt(RetrievalAttempt attempt, Instant now) {
        attemptTimer = scheduleAndCancelOnClose(Duration.between(now, attempt.at()), () -> {
            attemptTimer = null;
            pendingAttempt = attempt;
            triggerMeAndParentsInNewWave("retrieval attempt due");
        });
    }

    private void cancelAttempt() {
        closeIfNotNull(attemptTimer);
        attemptTimer = null;
    }

    /// Starts a retrieval from `from`, and stores its outcome for the next wave when it ends.
    private void startRetrieval(Instant from, Instant now) {
        fetchHistory.recordStart(now);
        retrieval.fetch(from).whenComplete((prices, failure) -> runner.executor().tryExecute("onTariffPricesRetrieved", () -> {
            pendingOutcome = failure == null ? new Retrieved(prices, from) : new Failed(failure, from);
            triggerInNewWave("prices retrieved");
        }));
    }
}
