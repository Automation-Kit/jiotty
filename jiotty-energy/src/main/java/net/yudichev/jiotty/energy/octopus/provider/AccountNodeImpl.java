package net.yudichev.jiotty.energy.octopus.provider;

import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;

/// An [AccountNode] that polls through a [RetryableOperationExecutor], and skips a poll that falls due while a fetch is running or retrying. A fetch that
/// ends, and each failed attempt of a fetch, is stored until the next wave, which decides the new state:
///
/// - a successful fetch makes a new [Loaded];
/// - until a fetch has succeeded, a failure makes an [Unavailable], unless the state already holds a failure with the same
///   [HumanReadableExceptionMessage#humanReadableMessage(Throwable)], so a run of identical failures changes the state once;
/// - once a fetch has succeeded, the state after a failure is the latest [Loaded].
///
/// The first poll waits for the poll interval after the start of the latest fetch that the [FetchHistory] records, or for
/// [OctopusPriceSlots#FAILURE_RETRY_DELAY] while the state is not [Loaded]; with no fetch recorded, the first poll starts at once. The [FetchHistory]
/// outlives the node, so the first poll of a node created in its place is timed from the latest fetch of this node.
final class AccountNodeImpl extends BaseServerNode implements AccountNode {
    private final RetryableOperationExecutor retryExecutor;
    private final Duration pollInterval;
    private final OctopusAccountService accountService;
    private final FetchHistory fetchHistory;

    private boolean pendingPoll;
    /// The failure of an attempt that the running fetch retries, since the last wave; `null` when there is none.
    private @Nullable Throwable pendingAttemptFailure;
    /// The end of the fetch that has ended since the last wave, a [Loaded] or an [Unavailable]; `null` when none has.
    private @Nullable AccountState pendingFetchEnd;
    private @Nullable AccountState state;
    /// The fetch that is running or retrying; `null` while none is. Cancelling it stops its retries, each of which calls Octopus with the user's API key.
    private @Nullable CompletableFuture<OctopusAccountData> fetch;
    /// `null` before the first wave.
    private @Nullable Closeable pollTimer;

    @Inject
    public AccountNodeImpl(@PollRetry RetryableOperationExecutor retryExecutor,
                           @AccountPollInterval Duration pollInterval,
                           @Assisted GraphRunner runner,
                           @Assisted OctopusAccountService accountService,
                           @Assisted FetchHistory fetchHistory,
                           @Assisted Optional<AccountState> state) {
        super(runner);
        this.retryExecutor = checkNotNull(retryExecutor);
        this.pollInterval = checkNotNull(pollInterval);
        this.accountService = checkNotNull(accountService);
        this.fetchHistory = checkNotNull(fetchHistory);
        this.state = state.orElse(null);
    }

    /// Stops the polls and cancels the running fetch.
    @Override
    public void close() {
        if (fetch != null) {
            fetch.cancel(false);
        }
        closeIfNotNull(pollTimer);
        super.close();
    }

    @Override
    public @Nullable AccountState state() {
        return state;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: pendingPoll={}, pendingAttemptFailure={}, pendingFetchEnd={}, state={}, fetchRunning={}, pollTimerArmed={}",
                     when, pendingPoll, pendingAttemptFailure, pendingFetchEnd, state, fetch != null, pollTimer != null);
    }

    @Override
    protected boolean doWave() {
        Instant now = waveTime();
        if (pollTimer == null) {
            startPolling(now);
        }
        AccountState previousState = state;
        Throwable pendingAttemptFailure = this.pendingAttemptFailure;
        this.pendingAttemptFailure = null;
        if (pendingAttemptFailure != null) {
            onFailure(pendingAttemptFailure);
        }
        AccountState pendingFetchEnd = this.pendingFetchEnd;
        this.pendingFetchEnd = null;
        switch (pendingFetchEnd) {
            case null -> {
            }
            case Loaded loaded -> {
                fetch = null;
                state = loaded;
            }
            case Unavailable(Throwable cause) -> {
                fetch = null;
                logger.info("Failed to fetch Octopus account after retries", cause);
                onFailure(cause);
            }
        }
        if (pendingPoll) {
            pendingPoll = false;
            poll(now);
        }
        return state != previousState;
    }

    private void startPolling(Instant now) {
        Instant firstPoll = fetchHistory.earliestNextStart(now, state instanceof Loaded ? pollInterval : FAILURE_RETRY_DELAY);
        pollTimer = runner.executor().scheduleAtFixedRate(Duration.between(now, firstPoll), pollInterval, () -> {
            pendingPoll = true;
            triggerMeAndParentsInNewWave("account poll due");
        });
    }

    private void onFailure(Throwable failure) {
        if (state instanceof Loaded
            || state instanceof Unavailable(Throwable cause) && humanReadableMessage(cause).equals(humanReadableMessage(failure))) {
            return;
        }
        state = new Unavailable(failure);
    }

    private void poll(Instant now) {
        if (fetch != null) {
            logger.debug("Skipping the Octopus account poll: the previous fetch is still retrying");
            return;
        }
        logger.debug("Polling Octopus account");
        fetchHistory.recordStart(now);
        CompletableFuture<OctopusAccountData> newFetch = retryExecutor.withBackOffAndRetry(
                "octopusAccount.getAccount",
                accountService::getAccount,
                (_, throwable) -> runner.executor().tryExecute("onOctopusAccountAttemptFailed", () -> {
                    pendingAttemptFailure = throwable;
                    triggerInNewWave("account fetch attempt failed");
                }));
        fetch = newFetch;
        newFetch.whenComplete((account, failure) -> runner.executor().tryExecute("onOctopusAccountFetched", () -> {
            pendingFetchEnd = failure == null ? new Loaded(account) : new Unavailable(failure);
            triggerInNewWave("account fetched");
        }));
    }

    interface Factory {
        /// @param fetchHistory the start of the latest account fetch, which spaces the first poll
        /// @param state        the [AccountState] to start from; empty to start with none
        AccountNodeImpl create(GraphRunner runner, OctopusAccountService accountService, FetchHistory fetchHistory, Optional<AccountState> state);
    }

    /// Qualifies the interval between account fetches: the value passed to [OctopusEnergyPriceServiceModule.Builder#withAccountPollInterval], or
    /// [OctopusEnergyProviderService#DEFAULT_ACCOUNT_POLL_INTERVAL] when that is not called.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccountPollInterval {
    }

    /// Qualifies the long-window [RetryableOperationExecutor] used by the background account poll (see [#retryExecutor]). Annotated so the binding never
    /// collides with, or accidentally captures, an unannotated executor of the same type in the parent injector.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface PollRetry {
    }
}
