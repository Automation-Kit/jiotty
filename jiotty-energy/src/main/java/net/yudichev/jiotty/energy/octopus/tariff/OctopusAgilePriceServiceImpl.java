package net.yudichev.jiotty.energy.octopus.tariff;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.OctopusStreams;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Failed;
import net.yudichev.jiotty.energy.octopus.tariff.RetrievalOutcome.Retrieved;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import net.yudichev.jiotty.timeseriescache.TimeSeriesStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.temporal.ChronoUnit.DAYS;
import static net.yudichev.jiotty.common.inject.Bindings.Rng;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.SLOT_LENGTH_SEC;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.floorToSlot;

/// The price service of an Agile tariff. One instance per `(productCode, tariffCode)`, serving every subscriber on that tariff. It reads its rates from the
/// same `octopus.rates:<productCode>:<tariffCode>` [TimeSeriesStream] as every other reader of those rates, so concurrent reads of the same
/// `(region, day)` share one Octopus call.
///
/// The daily retrieval runs as Octopus' publication window opens. Polls follow, more often while the window is open than after it has closed, until the
/// prices reach the end of the last Agile day that Octopus should have published by then; Octopus' publication time varies by hours.
///
/// Subscribers receive a [Prices] whenever the result changes. A failed retrieval is retried, and is published as a [Failure.PriceRetrievalError] as the
/// next publication window opens if no prices have been retrieved by then.
public final class OctopusAgilePriceServiceImpl extends BaseOctopusTariffPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceImpl.class);

    /// Short enough that subscribers receive tomorrow's prices within minutes of Octopus publishing them. Long enough that the polls of one publication window
    /// make a modest number of calls on the rates stream, which every reader of this tariff's rates shares.
    @VisibleForTesting
    static final Duration PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(5);
    /// The poll interval once the publication window has closed before the expected prices were published, suited to a publication that is already hours
    /// late.
    private static final Duration LATE_PUBLICATION_POLL_INTERVAL = Duration.ofMinutes(30);

    private final TimeSeriesCache cache;
    private final OctopusRegionService regionService;
    private final String productCode;

    private TimeSeriesStream<StandardUnitRate> ratesStream;

    @Inject
    public OctopusAgilePriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        @Rng DoubleSupplier backoffRng,
                                        TimeSeriesCache cache,
                                        TaskFailureReporter taskFailureReporter,
                                        @Assisted OctopusRegionService regionService,
                                        @Assisted("productCode") String productCode,
                                        @Assisted("tariffCode") String tariffCode) {
        super(executorProvider, timeProvider, backoffRng, taskFailureReporter, tariffCode);
        this.cache = checkNotNull(cache);
        this.regionService = checkNotNull(regionService);
        this.productCode = checkNotNull(productCode);
    }

    @Override
    protected void doStart0() {
        ratesStream = OctopusStreams.ratesStream(cache, regionService, timeProvider, productCode, tariffCode());
    }

    @Override
    public LocalTime dailyRetrievalTime() {
        return AgilePublicationWindow.WINDOW_START;
    }

    /// Reads two days of rates from the slot of `from` through the cache-backed [TimeSeriesStream]: a slot that the cache does not hold is fetched from
    /// Octopus once, and a cached slot is read from the cache. The returned future completes with the rates assembled into [Prices].
    @Override
    public CompletableFuture<Prices> fetch(Instant from) {
        Instant alignedFrom = floorToSlot(from);
        Instant alignedTo = alignedFrom.plus(2, DAYS);
        logger.debug("[{}] Requesting prices from {} to {}", tariffCode(), alignedFrom, alignedTo);
        return ratesStream.readRange(alignedFrom, alignedTo)
                          .thenApplyAsync(this::toPrices, executor());
    }

    /// Converts `ratesByInstant`, one Agile rate per slot in time order, to [Prices], reading only final fields and changing none, because the conversion
    /// runs on the executor outside the graph's waves.
    private Prices toPrices(ImmutableMap<Instant, StandardUnitRate> ratesByInstant) {
        checkArgument(!ratesByInstant.isEmpty(), "Octopus returned no rates");
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

    /// Plans a retry of a failed retrieval from the same instant after [OctopusPriceSlots#FAILURE_RETRY_DELAY], and another poll from the same instant while
    /// the retrieved prices end before [AgilePublicationWindow#expectedCoverage].
    @Override
    public RetrievalPlan plan(RetrievalOutcome outcome, Instant now) {
        return switch (outcome) {
            case Retrieved(Prices prices, Instant from) -> pollUntilExpectedPricesPublished(from, prices.profileEnd(), now);
            case Failed(Throwable cause, Instant from) -> {
                logger.info("[{}] Failed retrieving Octopus prices from {}, will retry in {}", tariffCode(), from, FAILURE_RETRY_DELAY, cause);
                Instant retryAt = now.plus(FAILURE_RETRY_DELAY);
                yield RetrievalPlan.builder()
                                   .setAttempt(RetrievalAttempt.builder().setAt(retryAt).setFrom(from).build())
                                   .setNextRefreshTime(retryAt)
                                   .build();
            }
        };
    }

    /// Plans another attempt from `requestedFrom` when `endOfNewestPricePeriod` is before [AgilePublicationWindow#expectedCoverage] at `now`: after
    /// [#PUBLICATION_POLL_INTERVAL] while the publication window is open, and after [#LATE_PUBLICATION_POLL_INTERVAL] once it has closed.
    private RetrievalPlan pollUntilExpectedPricesPublished(Instant requestedFrom, Instant endOfNewestPricePeriod, Instant now) {
        Instant expectedCoverage = AgilePublicationWindow.expectedCoverage(now);
        if (!endOfNewestPricePeriod.isBefore(expectedCoverage)) {
            return RetrievalPlan.builder().setNextRefreshTime(AgilePublicationWindow.nextWindowStartAfter(now)).build();
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
        Instant pollAt = now.plus(delay);
        // Octopus publish one Agile day at a time. If the prices end before the start of the last Agile day that Octopus should have published by now,
        // that day's prices do not join on to them, so the next refresh time is the start of the next publication window.
        return RetrievalPlan.builder()
                            .setAttempt(RetrievalAttempt.builder().setAt(pollAt).setFrom(requestedFrom).build())
                            .setNextRefreshTime(endOfNewestPricePeriod.isBefore(AgilePublicationWindow.expectedPublicationStart(now))
                                                ? AgilePublicationWindow.nextWindowStartAfter(now)
                                                : pollAt)
                            .build();
    }

    /// Returns `false`, so a failure is published as the next publication window opens if no prices have been retrieved by then.
    @Override
    public boolean publishesFailureAtOnce(boolean pricesPublished) {
        return false;
    }

    private static Prices constructPrices(Instant startOfOldestPricePeriod, List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(SLOT_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }
}
