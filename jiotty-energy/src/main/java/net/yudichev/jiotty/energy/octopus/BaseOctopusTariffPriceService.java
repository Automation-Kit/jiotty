package net.yudichev.jiotty.energy.octopus;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.observer.ObservableValue;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.UK_ZONE;

/// The base of the [OctopusTariffPriceService] of one tariff. It retrieves the tariff's prices on start and every day at a UK clock time, in the way that
/// each subclass implements, and publishes the results on the executor that it obtains at start.
abstract class BaseOctopusTariffPriceService extends BaseLifecycleComponent implements OctopusTariffPriceService {
    private static final Logger logger = LogManager.getLogger(BaseOctopusTariffPriceService.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final JobScheduler jobScheduler;
    private final LocalTime dailyRetrievalTime;
    private final String tariffCode;
    /// The most recent result; `null` until the first one is produced. Concurrent because [#getPrices()] reads it on the caller's thread.
    private final ObservableValue<@Nullable PriceResult> priceResult;
    /// When the prices are next expected to reach further than they do now; `null` until the first retrieval completes or fails.
    private final ObservableValue<@Nullable Instant> nextRefreshTime = ObservableValue.simple(null);

    private SchedulingExecutor executor;
    private Closeable jobSchedule;
    /// The one attempt waiting to happen, a poll or a retry after a failure; `null` when none is scheduled.
    private @Nullable Closeable pendingAttempt;
    /// The failure of the latest retrieval that a subclass deferred; `null` when there is none to publish.
    private @Nullable Throwable deferredFailure;

    /// @param dailyRetrievalTime the UK clock time of the daily retrieval
    /// @param deliveryName       what a failed delivery to a subscriber is reported as
    BaseOctopusTariffPriceService(Provider<SchedulingExecutor> executorProvider,
                                  JobScheduler jobScheduler,
                                  TaskFailureReporter taskFailureReporter,
                                  LocalTime dailyRetrievalTime,
                                  String tariffCode,
                                  String deliveryName) {
        checkNotNull(taskFailureReporter);
        checkNotNull(deliveryName);
        priceResult = ObservableValue.concurrent(null, e -> taskFailureReporter.onTaskException(deliveryName, e));
        this.executorProvider = checkNotNull(executorProvider);
        this.jobScheduler = checkNotNull(jobScheduler);
        this.dailyRetrievalTime = checkNotNull(dailyRetrievalTime);
        this.tariffCode = checkNotNull(tariffCode);
    }

    @Override
    protected final void doStart() {
        executor = executorProvider.get();
        prepareRetrieval();
        executor.execute("retrieveOctopusPricesOnStart", this::retrieve);
        jobSchedule = jobScheduler.daily(executor, "Retrieve Octopus prices " + tariffCode, dailyRetrievalTime, UK_ZONE, this::onDailyRetrieval);
    }

    @Override
    protected final void doStop() {
        executor.execute(name(), () -> closeSafelyIfNotNull(logger, pendingAttempt, jobSchedule));
    }

    @Override
    public final Optional<PriceResult> getPrices() {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(priceResult.get()));
    }

    @Override
    public final Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(result -> {
            if (result != null) {
                consumer.accept(result);
            }
        }));
    }

    @Override
    public final Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
        return whenStartedAndNotLifecycling(() -> nextRefreshTime.subscribe(executor, value -> {
            if (value != null) {
                consumer.accept(value);
            }
        }));
    }

    /// Prepares what [#retrieve()] needs; runs in [#doStart()] before the first retrieval.
    protected void prepareRetrieval() {
    }

    /// Retrieves the prices from now. Runs on the executor: on start, at the daily retrieval, and from an attempt that the subclass scheduled.
    ///
    /// @implSpec Must schedule any further attempt through [#scheduleAttempt], so that at most one is pending.
    protected abstract void retrieve();

    /// @return the executor that the retrieval runs on and every result is published on
    protected final SchedulingExecutor executor() {
        return executor;
    }

    protected final String tariffCode() {
        return tariffCode;
    }

    /// @return the result that subscribers hold, `null` before the first one
    protected final @Nullable PriceResult latestResult() {
        return priceResult.get();
    }

    /// Once `retrieval` completes, calls `resultHandler` with its result, or `failureHandler` with its failure, in a task on the executor, unless this
    /// service has stopped by then.
    ///
    /// @param <T>      the type of the retrieval's result
    /// @param taskName identifies the task in a failure report
    protected final <T> void handleOutcome(CompletableFuture<T> retrieval,
                                           String taskName,
                                           Consumer<? super T> resultHandler,
                                           Consumer<Throwable> failureHandler) {
        retrieval.whenComplete((result, failure) -> executor.tryExecute(taskName, () -> ifNotStopped(() -> {
            if (failure == null) {
                resultHandler.accept(result);
            } else {
                failureHandler.accept(failure);
            }
        })));
    }

    /// Schedules `attempt` after `delay` on the executor, cancelling the attempt that was waiting.
    protected final void scheduleAttempt(Duration delay, Runnable attempt) {
        cancelPendingAttempt();
        pendingAttempt = executor.schedule(delay, attempt);
    }

    /// Schedules `attempt` after [OctopusPriceSlots#FAILURE_RETRY_DELAY] and publishes the time of that attempt as the next refresh time.
    ///
    /// @param now the current instant
    protected final void scheduleRetry(Runnable attempt, Instant now) {
        scheduleAttempt(FAILURE_RETRY_DELAY, attempt);
        publishNextRefreshTime(now.plus(FAILURE_RETRY_DELAY));
    }

    protected final void cancelPendingAttempt() {
        closeSafelyIfNotNull(logger, pendingAttempt);
        pendingAttempt = null;
    }

    /// Stores `failure` for the next daily retrieval to publish; [#publishPrices] drops it.
    protected final void deferFailure(Throwable failure) {
        deferredFailure = failure;
    }

    /// Publishes `prices` and drops the deferred failure.
    protected final void publishPrices(Prices prices) {
        deferredFailure = null;
        publishIfChanged(prices);
    }

    protected final void publishFailure(Throwable failure) {
        publishIfChanged(new Failure.PriceRetrievalError(failure));
    }

    /// Publishes `value` unless subscribers already hold it, so a next refresh time that stays the same across a day of retrievals notifies subscribers
    /// once.
    protected final void publishNextRefreshTime(Instant value) {
        if (!value.equals(nextRefreshTime.get())) {
            nextRefreshTime.accept(value);
        }
    }

    /// Publishes the failure that a subclass deferred, so a tariff whose retrievals keep failing reports it at least once a day, then retrieves again.
    private void onDailyRetrieval() {
        cancelPendingAttempt();
        if (deferredFailure != null) {
            publishFailure(deferredFailure);
            deferredFailure = null;
        }
        retrieve();
    }

    /// Publishes `result` unless [PriceResults#isUnchanged] holds for it and the published result, so a retrieval that returns the same prices again
    /// notifies no subscriber.
    private void publishIfChanged(PriceResult result) {
        if (PriceResults.isUnchanged(result, priceResult.get())) {
            return;
        }
        if (result instanceof Prices prices) {
            logger.info("[{}] Prices now from {} till {}", tariffCode, prices.profileStart(), prices.profileEnd());
        }
        priceResult.accept(result);
    }
}
