package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StrictMath.toIntExact;
import static java.time.temporal.ChronoUnit.DAYS;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;

/// App-scope per-tariff Agile-prices service. One instance per `(productCode, tariffCode)`, created lazily by [OctopusAgilePriceServiceRegistry] and shared
/// across every user whose Octopus account points at that tariff. Pricing data flows through the same `octopus.rates:<productCode>:<tariffCode>`
/// [TimeSeriesStream] that the analytics layer consumes, so the live scheduler's refresh and any IOG report cold-fill in the same `(region, day)` share the
/// same Octopus call.
///
/// Retrieval is anchored to Octopus's publication window: one attempt as it opens, then a poll — brisk while it is open, slower once it has closed — until the
/// profile reaches what Octopus should by then have published, because publication times vary by hours.
///
/// Subscribers receive [Either]`<Prices, Failure>` notifications whenever the result changes; connector failures surface as [Failure.PriceRetrievalError]. The
/// [Failure.IncompatibleTariff] path is owned by the user-scope provider, not this impl — by construction this instance only exists for an Agile tariff.
public final class OctopusAgilePriceServiceImpl extends BaseLifecycleComponent implements OctopusAgilePriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceImpl.class);

    private static final long PRICE_PERIOD_LENGTH_MIN = 30;
    private static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(PRICE_PERIOD_LENGTH_MIN));
    private static final long PRICE_PERIOD_LENGTH_SEC_L = PRICE_PERIOD_LENGTH_MIN * 60L;
    private static final Duration FAILURE_RETRY_DELAY = Duration.ofMinutes(15);
    /// Tight enough that tomorrow's prices reach the planner within minutes of Octopus publishing them, sparse enough that a whole window of polls stays a
    /// modest number of calls on a stream every user of this tariff shares.
    private static final Duration PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(5);
    /// Cadence once the window has closed on prices that never came: still chasing them through the night, at a rate that suits a publication already hours
    /// late.
    private static final Duration LATE_PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(30);

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final TimeSeriesStream<StandardUnitRate> ratesStream;
    private final String tariffCode;
    private final JobScheduler jobScheduler;
    /// The latest price-or-failure result, empty until the first one is produced. New subscribers receive the present value immediately.
    private final ObservableValue<Optional<Either<Prices, Failure>>> priceResult = ObservableValue.concurrent(Optional.empty());
    /// When the profile is next expected to reach further than it does now; `null` until the first retrieval settles. Confined to [#executor], as is every
    /// touch of it including subscription.
    private final ObservableValue<@Nullable Instant> nextRefreshTime = ObservableValue.simple(null);

    private SchedulingExecutor executor;
    private Closeable jobSchedule;
    /// The one attempt waiting to happen — a publication poll or a post-failure retry, never both, since each is armed only after the previous is cancelled.
    private @Nullable Closeable pendingAttemptSchedule;
    private @Nullable Throwable lastFailure;

    @Inject
    public OctopusAgilePriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        TimeSeriesCache cache,
                                        JobScheduler jobScheduler,
                                        @Assisted OctopusRegionService regionService,
                                        @Assisted("productCode") String productCode,
                                        @Assisted("tariffCode") String tariffCode) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        checkNotNull(cache, "cache");
        checkNotNull(regionService, "regionService");
        checkNotNull(productCode, "productCode");
        this.tariffCode = checkNotNull(tariffCode);
        this.jobScheduler = checkNotNull(jobScheduler);
        ratesStream = OctopusStreams.ratesStream(cache, regionService, productCode, tariffCode);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        executor.execute("retrieveOctopusPricesOnStart", () -> retrieveOctopusPrices(timeProvider.currentInstant()));
        jobSchedule = jobScheduler.daily(executor,
                                         "Retrieve Octopus Prices " + tariffCode,
                                         AgilePublicationWindow.WINDOW_START,
                                         AgilePublicationWindow.ZONE,
                                         this::onPublicationWindowOpen);
    }

    @Override
    protected void doStop() {
        if (executor != null) {
            executor.execute(name(), () -> closeSafelyIfNotNull(logger, pendingAttemptSchedule, jobSchedule));
        }
    }

    @Override
    public Optional<Either<Prices, Failure>> getPrices() {
        return whenStartedAndNotLifecycling(priceResult);
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(result -> result.ifPresent(consumer)));
    }

    @Override
    public Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
        return whenStartedAndNotLifecycling(() -> nextRefreshTime.subscribe(executor, value -> {
            if (value != null) {
                consumer.accept(value);
            }
        }));
    }

    /// Starts the day's retrieval as Octopus's publication window opens. A failure that never reached subscribers is reported now, so a tariff whose fetches
    /// have been failing surfaces that at least once a day rather than staying silently stale.
    private void onPublicationWindowOpen() {
        cancelPendingAttempt();
        if (lastFailure != null) {
            priceResult.accept(Optional.of(Either.right(new Failure.PriceRetrievalError(lastFailure))));
            lastFailure = null;
        }
        retrieveOctopusPrices(timeProvider.currentInstant());
    }

    private void retrieveOctopusPrices(Instant periodFrom) {
        var alignedFrom = floorToSlot(periodFrom);
        var alignedTo = alignedFrom.plus(2, DAYS);
        logger.debug("[{}] Requesting prices from {} to {}", tariffCode, alignedFrom, alignedTo);
        fetchAgilePrices(alignedFrom, alignedTo)
                .thenAcceptAsync(this::publishIfChanged, executor)
                .whenCompleteAsync((_, throwable) -> {
                    if (throwable != null) {
                        handleFailure(alignedFrom, throwable);
                    }
                }, executor);
    }

    /// Reads the requested range through the cache-backed [TimeSeriesStream]; new slots flow through to Octopus once, cached slots return immediately. Result
    /// is assembled into [Prices]; if the Octopus call fails the returned future fails too (the failure flows to [#handleFailure]).
    private CompletableFuture<Either<Prices, Failure>> fetchAgilePrices(Instant from, Instant to) {
        return ratesStream.readRange(from, to)
                          .thenApplyAsync(ratesByInstant -> Either.left(handleOctopusPrices(ratesByInstant, from)), executor);
    }

    /// Publishes `result` unless subscribers already hold exactly it. The publication poll runs for as long as hours over a profile Octopus has not extended
    /// yet, and every notification costs each subscriber a full replan.
    private void publishIfChanged(Either<Prices, Failure> result) {
        Optional<Either<Prices, Failure>> newResult = Optional.of(result);
        if (newResult.equals(priceResult.get())) {
            return;
        }
        result.getLeft().ifPresent(prices -> logger.info("[{}] Prices now from {} till {}", tariffCode, prices.profileStart(), prices.profileEnd()));
        priceResult.accept(newResult);
    }

    private void handleFailure(Instant periodFrom, Throwable e) {
        cancelPendingAttempt();
        lastFailure = e;
        logger.info("[{}] Failed retrieving Octopus prices from {}, will retry in {}", tariffCode, periodFrom, FAILURE_RETRY_DELAY, e);
        pendingAttemptSchedule = executor.schedule(FAILURE_RETRY_DELAY, () -> retrieveOctopusPrices(periodFrom));
        publishNextRefreshTime(timeProvider.currentInstant().plus(FAILURE_RETRY_DELAY));
    }

    private Prices handleOctopusPrices(ImmutableMap<Instant, StandardUnitRate> ratesByInstant, Instant requestedFrom) {
        // A fresh result supersedes anything an earlier attempt scheduled, and retires the failure that attempt left to report.
        cancelPendingAttempt();
        lastFailure = null;
        checkArgument(!ratesByInstant.isEmpty(), "Octopus returned no rates");
        // ratesByInstant is in chronological order (Resolution.halfHourly maps to ascending Instants).
        ImmutableList<StandardUnitRate> rates = ratesByInstant.values().asList();
        Instant startOfOldestPricePeriod = ratesByInstant.keySet().iterator().next();
        Instant endOfNewestPricePeriod = rates.get(rates.size() - 1).validTo();
        ImmutableList.Builder<Double> pricesPerPeriod = ImmutableList.builderWithExpectedSize(rates.size());
        for (var rate : rates) {
            pricesPerPeriod.add(rate.valueIncVat());
        }
        logger.debug("[{}] Received prices from {} till {}", tariffCode, startOfOldestPricePeriod, endOfNewestPricePeriod);

        pollUntilExpectedPricesPublished(requestedFrom, endOfNewestPricePeriod);
        return constructPrices(startOfOldestPricePeriod, pricesPerPeriod.build());
    }

    private void cancelPendingAttempt() {
        closeSafelyIfNotNull(logger, pendingAttemptSchedule);
        pendingAttemptSchedule = null;
    }

    /// Schedules another attempt when the fetch came back short of what Octopus should have published by now. Polling is brisk while the publication window is
    /// open and slows once it has closed, but it does not stop: a publication hours late still has the whole night to arrive, and the overnight plan needs it.
    private void pollUntilExpectedPricesPublished(Instant requestedFrom, Instant endOfNewestPricePeriod) {
        Instant now = timeProvider.currentInstant();
        Instant expectedCoverage = AgilePublicationWindow.expectedCoverage(now);
        if (!endOfNewestPricePeriod.isBefore(expectedCoverage)) {
            publishNextRefreshTime(AgilePublicationWindow.nextWindowStartAfter(now));
            return;
        }
        boolean windowOpen = AgilePublicationWindow.isOpen(now);
        Duration delay = windowOpen ? PUBLICATION_POLL_INTERVAL : LATE_PUBLICATION_POLL_INTERVAL;
        if (windowOpen) {
            logger.debug("[{}] Prices only until {}, expected until {}, retrying in {}",
                         tariffCode, endOfNewestPricePeriod, expectedCoverage, delay);
        } else {
            logger.info("[{}] Prices only until {}, expected until {} and the publication window has closed, retrying in {}",
                        tariffCode, endOfNewestPricePeriod, expectedCoverage, delay);
        }
        pendingAttemptSchedule = executor.schedule(delay, () -> retrieveOctopusPrices(requestedFrom));
        // The publication being chased starts where the Agile day it covers begins; a profile ending before that has a hole it will not fill, so the profile
        // grows no earlier than the next window.
        publishNextRefreshTime(endOfNewestPricePeriod.isBefore(AgilePublicationWindow.expectedPublicationStart(now))
                               ? AgilePublicationWindow.nextWindowStartAfter(now)
                               : now.plus(delay));
    }

    /// Publishes `value` unless subscribers already hold it, so the steady state — a next-window instant that stands for a day — notifies once rather than
    /// after every retrieval.
    private void publishNextRefreshTime(Instant value) {
        if (!value.equals(nextRefreshTime.get())) {
            nextRefreshTime.accept(value);
        }
    }

    private static Prices constructPrices(Instant startOfOldestPricePeriod, List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }

    private static Instant floorToSlot(Instant t) {
        long sec = t.getEpochSecond();
        return Instant.ofEpochSecond(sec - (sec % PRICE_PERIOD_LENGTH_SEC_L));
    }
}
