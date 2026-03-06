package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StrictMath.toIntExact;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.Bindings.Dependency;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

final class OctopusEnergyPriceServiceImpl extends BaseLifecycleComponent implements EnergyPriceService {
    private static final Logger logger = LoggerFactory.getLogger(OctopusEnergyPriceServiceImpl.class);

    private static final long PRICE_PERIOD_LENGTH_MIN = 30;
    private static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(PRICE_PERIOD_LENGTH_MIN));
    private static final Duration RETRY_DELAY = Duration.ofMinutes(15);

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusEnergy octopusEnergy;
    private final JobScheduler jobScheduler;
    private final ZoneId zoneId;
    private final Listeners<Prices> listeners = new Listeners<>();

    private Instant startOfOldestPricePeriod;
    private volatile List<Double> pricesPerPeriod;
    private SchedulingExecutor executor;
    private Closeable jobSchedule;
    @Nullable
    private Closeable retrySchedule;
    @Nullable
    private Throwable lastFailure;

    @Inject
    public OctopusEnergyPriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                         CurrentDateTimeProvider timeProvider,
                                         OctopusEnergy octopusEnergy,
                                         JobScheduler jobScheduler,
                                         @Dependency ZoneId zoneId) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.jobScheduler = checkNotNull(jobScheduler);
        this.zoneId = checkNotNull(zoneId);
    }

    @Override
    public Optional<Prices> getPrices() {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(pricesPerPeriod)
                                                          .map(this::constructPrices));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Prices> consumer) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor, this::getPrices, consumer));
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        executor.submit(() -> retrieveOctopusPrices(timeProvider.currentInstant()));
        jobSchedule = jobScheduler.daily(executor, "Retrieve Octopus Prices", LocalTime.of(16, 5),
                                         () -> {
                                             // stop retries if they overflow until next day
                                             if (retrySchedule != null) {
                                                 logger.warn("Retries overran until next day, stopping them", lastFailure);
                                                 retrySchedule.close();
                                                 retrySchedule = null;
                                                 lastFailure = null;
                                             }
                                             retrieveOctopusPrices(timeProvider.currentInstant());
                                         });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, retrySchedule, jobSchedule);
    }

    private Prices constructPrices(List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }

    private void retrieveOctopusPrices(Instant periodFrom) {
        var periodTo = periodFrom.plus(2, DAYS);
        logger.info("Requesting prices from {} to {}", periodFrom, periodTo);
        octopusEnergy.getAgilePrices(periodFrom, periodTo)
                     .thenAcceptAsync(rates -> listeners.notify(handleOctopusPrices(rates, periodFrom)), executor)
                     .whenCompleteAsync((_, throwable) -> {
                         if (throwable != null) {
                             handleFailure(periodFrom, throwable);
                         }
                     }, executor);
    }

    private void handleFailure(Instant periodFrom, Throwable e) {
        lastFailure = e;
        logger.info("Failed retrieving Octopus prices from {}, will retry in 15 min", periodFrom, e);
        scheduleRetry(periodFrom);
    }

    private Prices handleOctopusPrices(List<StandardUnitRate> rates, Instant requestedFrom) {
        retrySchedule = null;
        checkArgument(!rates.isEmpty(), "Octopus returned empty list of rates");
        // newest first
        startOfOldestPricePeriod = rates.getLast().validFrom();
        logger.info("Received prices from {} till {}", startOfOldestPricePeriod, rates.getFirst().validTo());
        logger.debug("Prices received: {}", rates);
        var newPricesPerPeriodBuilder = ImmutableList.<Double>builder();
        for (int i = rates.size() - 1; i >= 0; i--) {
            StandardUnitRate rate = rates.get(i);
            int j = rates.size() - i - 1;
            var expectedStartTime = startOfOldestPricePeriod.plus(j * PRICE_PERIOD_LENGTH_MIN, MINUTES);
            var expectedEndTime = expectedStartTime.plus(PRICE_PERIOD_LENGTH_MIN, MINUTES);
            checkArgument(rate.validFrom().equals(expectedStartTime) && rate.validTo().equals(expectedEndTime),
                          "Element %s in received rates must have start time %s and end time %s but was %s: %s",
                          i, expectedStartTime, expectedEndTime, rates);
            newPricesPerPeriodBuilder.add(rate.valueIncVat());
        }
        pricesPerPeriod = newPricesPerPeriodBuilder.build();

        var requestedFromLocalDateTime = LocalDateTime.ofInstant(requestedFrom, zoneId);
        if (requestedFromLocalDateTime.toLocalTime().getHour() >= 16) {
            // typically, when started after 16:00, prices are returned until the next day 23:00; if not, schedule retry every 15 min
            Instant shouldBeAvailableUntil = requestedFromLocalDateTime
                    .plusDays(1).withHour(23).withMinute(0).withSecond(0).withNano(0)
                    .atZone(zoneId)
                    .toInstant();
            Instant pricesUntil = rates.getFirst().validTo();
            if (pricesUntil.isBefore(shouldBeAvailableUntil)) {
                logger.info("Returned prices are only until {}, but expected them to be until {}, retry in 15 min",
                            pricesUntil, shouldBeAvailableUntil);
                scheduleRetry(requestedFrom);
            }
        }
        return constructPrices(pricesPerPeriod);
    }

    private void scheduleRetry(Instant requestedFrom) {
        retrySchedule = executor.schedule(RETRY_DELAY, () -> retrieveOctopusPrices(requestedFrom));
    }
}
