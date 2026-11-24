package net.yudichev.jiotty.energy.octopus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.temporal.ChronoUnit.DAYS;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH_SEC;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.floorToSlot;

/// The price service of an Agile tariff. One instance per `(productCode, tariffCode)`, serving every subscriber on that tariff. Its rates flow through the
/// same `octopus.rates:<productCode>:<tariffCode>` [TimeSeriesStream] as every other reader of those rates, so concurrent reads of the same
/// `(region, day)` share one Octopus call.
///
/// Retrieval is anchored to Octopus' publication window: one attempt as it opens, then a poll — brisk while it is open, slower once it has closed — until the
/// profile reaches what Octopus should by then have published, because publication times vary by hours.
///
/// Subscribers receive a [Prices] whenever the result changes. A failed retrieval is retried and published as a [Failure.PriceRetrievalError] as the next
/// publication window opens, unless prices arrive first.
public final class OctopusAgilePriceServiceImpl extends BaseOctopusTariffPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceImpl.class);

    /// Tight enough that tomorrow's prices reach the planner within minutes of Octopus publishing them, sparse enough that a whole window of polls stays a
    /// modest number of calls on a stream every user of this tariff shares.
    @VisibleForTesting
    static final Duration PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(5);
    /// Cadence once the window has closed on prices that never came: still chasing them through the night, at a rate that suits a publication already hours
    /// late.
    private static final Duration LATE_PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(30);

    private final CurrentDateTimeProvider timeProvider;
    private final TimeSeriesCache cache;
    private final OctopusRegionService regionService;
    private final String productCode;

    private TimeSeriesStream<StandardUnitRate> ratesStream;

    @Inject
    public OctopusAgilePriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        TimeSeriesCache cache,
                                        JobScheduler jobScheduler,
                                        TaskFailureReporter taskFailureReporter,
                                        @Assisted OctopusRegionService regionService,
                                        @Assisted("productCode") String productCode,
                                        @Assisted("tariffCode") String tariffCode) {
        super(executorProvider, jobScheduler, taskFailureReporter, AgilePublicationWindow.WINDOW_START, tariffCode, "Delivering the Agile prices");
        this.timeProvider = checkNotNull(timeProvider);
        this.cache = checkNotNull(cache);
        this.regionService = checkNotNull(regionService);
        this.productCode = checkNotNull(productCode);
    }

    @Override
    protected void prepareRetrieval() {
        ratesStream = OctopusStreams.ratesStream(cache, regionService, timeProvider, productCode, tariffCode());
    }

    @Override
    protected void retrieve() {
        retrieveOctopusPrices(timeProvider.currentInstant());
    }

    private void retrieveOctopusPrices(Instant periodFrom) {
        var alignedFrom = floorToSlot(periodFrom);
        var alignedTo = alignedFrom.plus(2, DAYS);
        logger.debug("[{}] Requesting prices from {} to {}", tariffCode(), alignedFrom, alignedTo);
        handleOutcome(fetchAgilePrices(alignedFrom, alignedTo),
                      "onAgilePrices",
                      prices -> {
                          // A fresh result supersedes any attempt that an earlier one scheduled.
                          cancelPendingAttempt();
                          pollUntilExpectedPricesPublished(alignedFrom, prices.profileEnd());
                          publishPrices(prices);
                      },
                      throwable -> handleFailure(alignedFrom, throwable));
    }

    /// Reads the requested range through the cache-backed [TimeSeriesStream]; new slots flow through to Octopus once, cached slots return immediately. Result
    /// is assembled into [Prices]; if the Octopus call fails the returned future fails too (the failure flows to [#handleFailure]).
    private CompletableFuture<Prices> fetchAgilePrices(Instant from, Instant to) {
        return ratesStream.readRange(from, to)
                          .thenApplyAsync(this::toPrices, executor());
    }

    private void handleFailure(Instant periodFrom, Throwable e) {
        deferFailure(e);
        logger.info("[{}] Failed retrieving Octopus prices from {}, will retry in {}", tariffCode(), periodFrom, FAILURE_RETRY_DELAY, e);
        scheduleRetry(() -> retrieveOctopusPrices(periodFrom), timeProvider.currentInstant());
    }

    /// Converts `ratesByInstant`, one Agile rate per slot in time order, to [Prices], reading only final fields and changing none, because the conversion
    /// runs on the executor before [#handleOutcome] checks that the service has not stopped.
    private Prices toPrices(ImmutableMap<Instant, StandardUnitRate> ratesByInstant) {
        checkArgument(!ratesByInstant.isEmpty(), "Octopus returned no rates");
        // ratesByInstant is in chronological order (Resolution.halfHourly maps to ascending Instants).
        ImmutableList<StandardUnitRate> rates = ratesByInstant.values().asList();
        Instant startOfOldestPricePeriod = ratesByInstant.keySet().iterator().next();
        // An Agile rate covers exactly one slot, so the newest slot's key plus one slot is where its rate ends.
        Instant endOfNewestPricePeriod = ratesByInstant.keySet().asList().getLast().plus(SLOT_LENGTH);
        ImmutableList.Builder<Double> pricesPerPeriod = ImmutableList.builderWithExpectedSize(rates.size());
        for (var rate : rates) {
            pricesPerPeriod.add(rate.valueIncVat());
        }
        logger.debug("[{}] Received prices from {} till {}", tariffCode(), startOfOldestPricePeriod, endOfNewestPricePeriod);
        return constructPrices(startOfOldestPricePeriod, pricesPerPeriod.build());
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
                         tariffCode(), endOfNewestPricePeriod, expectedCoverage, delay);
        } else {
            logger.info("[{}] Prices only until {}, expected until {} and the publication window has closed, retrying in {}",
                        tariffCode(), endOfNewestPricePeriod, expectedCoverage, delay);
        }
        scheduleAttempt(delay, () -> retrieveOctopusPrices(requestedFrom));
        // Octopus publish one Agile day at a time. If the prices end before the start of the last Agile day that Octopus should have published by now,
        // that day's prices do not join on to them, so the next refresh time is the start of the next publication window.
        publishNextRefreshTime(endOfNewestPricePeriod.isBefore(AgilePublicationWindow.expectedPublicationStart(now))
                               ? AgilePublicationWindow.nextWindowStartAfter(now)
                               : now.plus(delay));
    }

    private static Prices constructPrices(Instant startOfOldestPricePeriod, List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(SLOT_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }
}
