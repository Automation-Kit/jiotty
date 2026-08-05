package net.yudichev.jiotty.energy.octopus;

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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
/// [TimeSeriesStream] that the analytics layer consumes, so the live scheduler's daily 16:05 refresh and any IOG report cold-fill in the same `(region, day)`
/// share the same Octopus call.
///
/// Subscribers receive [Either]`<Prices, Failure>` notifications on every refresh; connector failures surface as [Failure.PriceRetrievalError]. The
/// [Failure.IncompatibleTariff] path is owned by the user-scope provider, not this impl — by construction this instance only exists for an Agile tariff.
public final class OctopusAgilePriceServiceImpl extends BaseLifecycleComponent implements OctopusAgilePriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceImpl.class);

    private static final long PRICE_PERIOD_LENGTH_MIN = 30;
    private static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(PRICE_PERIOD_LENGTH_MIN));
    private static final long PRICE_PERIOD_LENGTH_SEC_L = PRICE_PERIOD_LENGTH_MIN * 60L;
    private static final Duration RETRY_DELAY = Duration.ofMinutes(15);
    /// Octopus publishes Agile prices on UK time regardless of where a subscriber is, so the publication-time heuristic runs in Europe/London.
    private static final ZoneId PUBLICATION_ZONE = ZoneId.of("Europe/London");

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final TimeSeriesStream<StandardUnitRate> ratesStream;
    private final String tariffCode;
    private final JobScheduler jobScheduler;
    /// The latest price-or-failure result, empty until the first one is produced. New subscribers receive the present value immediately.
    private final ObservableValue<Optional<Either<Prices, Failure>>> priceResult = ObservableValue.concurrent(Optional.empty());

    private SchedulingExecutor executor;
    private Closeable jobSchedule;
    private @Nullable Closeable retrySchedule;
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
    public Optional<Either<Prices, Failure>> getPrices() {
        return whenStartedAndNotLifecycling(priceResult);
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(result -> result.ifPresent(consumer)));
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        executor.submit(() -> retrieveOctopusPrices(timeProvider.currentInstant()));
        jobSchedule = jobScheduler.daily(executor, "Retrieve Octopus Prices " + tariffCode, LocalTime.of(16, 5),
                                         () -> {
                                             if (retrySchedule != null) {
                                                 retrySchedule.close();
                                                 retrySchedule = null;
                                                 Either<Prices, Failure> result = Either.right(new Failure.PriceRetrievalError(checkNotNull(lastFailure)));
                                                 priceResult.accept(Optional.of(result));
                                                 lastFailure = null;
                                             }
                                             retrieveOctopusPrices(timeProvider.currentInstant());
                                         });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, retrySchedule, jobSchedule);
    }

    private void retrieveOctopusPrices(Instant periodFrom) {
        var alignedFrom = floorToSlot(periodFrom);
        var alignedTo = alignedFrom.plus(2, DAYS);
        logger.info("Requesting prices [{}] from {} to {}", tariffCode, alignedFrom, alignedTo);
        fetchAgilePrices(alignedFrom, alignedTo)
                .thenAcceptAsync(result -> priceResult.accept(Optional.of(result)), executor)
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

    private void handleFailure(Instant periodFrom, Throwable e) {
        lastFailure = e;
        logger.info("Failed retrieving Octopus prices [{}] from {}, will retry in {}", tariffCode, periodFrom, RETRY_DELAY, e);
        scheduleRetry(periodFrom);
    }

    private Prices handleOctopusPrices(ImmutableMap<Instant, StandardUnitRate> ratesByInstant, Instant requestedFrom) {
        retrySchedule = null;
        checkArgument(!ratesByInstant.isEmpty(), "Octopus returned no rates");
        // ratesByInstant is in chronological order (Resolution.halfHourly maps to ascending Instants).
        Instant startOfOldestPricePeriod = ratesByInstant.keySet().iterator().next();
        Instant endOfNewestPricePeriod = null;
        var pricesPerPeriod = new ArrayList<Double>(ratesByInstant.size());
        for (var rate : ratesByInstant.values()) {
            pricesPerPeriod.add(rate.valueIncVat());
            endOfNewestPricePeriod = rate.validTo();
        }
        logger.info("Received prices [{}] from {} till {}", tariffCode, startOfOldestPricePeriod, endOfNewestPricePeriod);

        Prices prices = constructPrices(startOfOldestPricePeriod, pricesPerPeriod);

        var requestedFromLocalDateTime = LocalDateTime.ofInstant(requestedFrom, PUBLICATION_ZONE);
        if (requestedFromLocalDateTime.toLocalTime().getHour() >= 16) {
            // typically, when started after 16:00, prices are returned until the next day 23:00; if not, schedule retry every 15 min
            Instant shouldBeAvailableUntil = requestedFromLocalDateTime
                    .plusDays(1).withHour(23).withMinute(0).withSecond(0).withNano(0)
                    .atZone(PUBLICATION_ZONE)
                    .toInstant();
            if (endOfNewestPricePeriod == null || endOfNewestPricePeriod.isBefore(shouldBeAvailableUntil)) {
                logger.info("Returned prices [{}] are only until {}, but expected them to be until {}, retry in {}",
                            tariffCode, endOfNewestPricePeriod, shouldBeAvailableUntil, RETRY_DELAY);
                scheduleRetry(requestedFrom);
            }
        }
        return prices;
    }

    private static Prices constructPrices(Instant startOfOldestPricePeriod, List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }

    private void scheduleRetry(Instant requestedFrom) {
        retrySchedule = executor.schedule(RETRY_DELAY, () -> retrieveOctopusPrices(requestedFrom));
    }

    private static Instant floorToSlot(Instant t) {
        long sec = t.getEpochSecond();
        return Instant.ofEpochSecond(sec - (sec % PRICE_PERIOD_LENGTH_SEC_L));
    }
}
