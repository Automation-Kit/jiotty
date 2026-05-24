package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.JobScheduler;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StrictMath.toIntExact;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.Bindings.Dependency;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

// TODO:commerce — remaining Stage D.5 work for this package (marker; depends on Stage D.1's TimeSeriesCache stream layout, runs before Stage F.1):
//  1. Move from user-app-scope to global-app-scope keyed by region. User-scope view becomes a thin resolver picking the right region's instance.
//  2. Share `TimeSeriesCache` streams with analytics so `(region, productCode, tariffCode, [from..to])` Octopus calls happen once, not per consumer.
//  3. Resolve the existing TODO inside [AgilePredictEnergyPriceServiceImpl] (also needs region as a global parameter).
//
//  TODO: "incompatible tariff" should be a permanent failure delivered via #subscribeToAuthState, which needs to be rebranded to PriceServiceState and
//   correctly combine both AuthState and tariff state. The, the energy integration should display this error clearly to the user instead of just sending a push
//   (BTW the push may now be unreliable, as I suspect sending a perm failure will stop the whole graph and the push will not be sent
//   - think how to handle this best.
//   As part of this, possibly add tariff code to the EnergyProviderIntegrationConfig which will be sent as part of the "successful" PriceServiceState and
//   displayed to the user.
final class OctopusAgilePriceServiceImpl extends BaseLifecycleComponent implements EnergyPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceImpl.class);

    private static final long PRICE_PERIOD_LENGTH_MIN = 30;
    private static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(PRICE_PERIOD_LENGTH_MIN));
    private static final Duration RETRY_DELAY = Duration.ofMinutes(15);
    private static final String AGILE_PRODUCT_CODE_PREFIX = "AGILE-";

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusEnergy octopusEnergy;
    private final String accountId;
    private final String apiKey;
    private final JobScheduler jobScheduler;
    private final ZoneId zoneId;
    private final Listeners<Either<Prices, Failure>> listeners = new Listeners<>();

    private OctopusAccountService accountService;
    private Instant startOfOldestPricePeriod;

    private @Nullable
    volatile Either<Prices, Failure> lastResult;
    private SchedulingExecutor executor;
    private Closeable jobSchedule;
    private @Nullable Closeable retrySchedule;
    private @Nullable Throwable lastFailure;

    @Inject
    public OctopusAgilePriceServiceImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        OctopusEnergy octopusEnergy,
                                        @AccountId String accountId,
                                        @ApiKey String apiKey,
                                        JobScheduler jobScheduler,
                                        @Dependency ZoneId zoneId) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountId = checkNotNull(accountId);
        this.apiKey = checkNotNull(apiKey);
        this.jobScheduler = checkNotNull(jobScheduler);
        this.zoneId = checkNotNull(zoneId);
    }

    @Override
    public Optional<Either<Prices, Failure>> getResult() {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(lastResult));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor, this::getResult, consumer));
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return accountService.subscribeToAuthState(consumer);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountService = octopusEnergy.account(accountId, apiKey);
        executor.submit(() -> retrieveOctopusPrices(timeProvider.currentInstant()));
        jobSchedule = jobScheduler.daily(executor, "Retrieve Octopus Prices", LocalTime.of(16, 5),
                                         () -> {
                                             if (retrySchedule != null) {
                                                 retrySchedule.close();
                                                 retrySchedule = null;
                                                 Either<Prices, Failure> result = Either.right(new Failure.PriceRetrievalError(checkNotNull(lastFailure)));
                                                 lastResult = result;
                                                 listeners.notify(result);
                                                 lastFailure = null;
                                             }
                                             retrieveOctopusPrices(timeProvider.currentInstant());
                                         });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, retrySchedule, jobSchedule, accountService);
    }

    private Prices constructPrices(List<Double> pricesPerPeriod) {
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, pricesPerPeriod.size(), pricesPerPeriod));
    }

    private void retrieveOctopusPrices(Instant periodFrom) {
        var periodTo = periodFrom.plus(2, DAYS);
        logger.info("Requesting prices from {} to {}", periodFrom, periodTo);
        fetchAgilePrices(periodFrom, periodTo)
                .thenAcceptAsync(result -> {
                    lastResult = result;
                    listeners.notify(result);
                }, executor)
                .whenCompleteAsync((_, throwable) -> {
                    if (throwable != null) {
                        handleFailure(periodFrom, throwable);
                    }
                }, executor);
    }

    /// Composes a per-`(from..to)` Agile-prices fetch from the connector tiers: pull the current tariff via the account service. If the active tariff is not an
    /// Agile tariff, short-circuit to [Failure.IncompatibleTariff]. Otherwise derive `(productCode, tariffCode, region)`, ask the region-bound service for the
    /// standard unit rates, and assemble [Prices] from them.
    private CompletableFuture<Either<Prices, Failure>> fetchAgilePrices(Instant from, Instant to) {
        return accountService.getAccount()
                             .thenCompose(account -> {
                                 Tariff tariff = extractCurrentTariff(account);
                                 String tariffCode = tariff.tariffCode();
                                 String productCode = tariffCode.substring(5, tariffCode.length() - 2);
                                 if (!productCode.startsWith(AGILE_PRODUCT_CODE_PREFIX)) {
                                     return CompletableFuture.completedFuture(
                                             Either.right(new Failure.IncompatibleTariff(tariffCode)));
                                 }
                                 char regionLetter = tariffCode.charAt(tariffCode.length() - 1);
                                 try (OctopusRegionService regionService = octopusEnergy.region(regionLetter)) {
                                     return regionService.getStandardUnitRates(productCode, tariffCode, from, to)
                                                         .thenApply(rates -> Either.left(handleOctopusPrices(rates, from)));
                                 }
                             });
    }

    private Tariff extractCurrentTariff(OctopusAccountData account) {
        return account.properties().stream().findFirst()
                      .flatMap(accountProperty -> accountProperty.electricityMeterPoints().stream().findFirst())
                      .flatMap(electricityMeterPoint -> electricityMeterPoint.tariffs().stream().filter(this::isCurrent).findFirst())
                      .orElseThrow(() -> new RuntimeException("Unable to extract current tariff from " + account));
    }

    private boolean isCurrent(Tariff tariff) {
        var now = timeProvider.currentInstant();
        return tariff.validFrom().equals(now) || tariff.validFrom().isBefore(now) && tariff.validTo().isAfter(now);
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
        Prices prices = constructPrices(newPricesPerPeriodBuilder.build());

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
        return prices;
    }

    private void scheduleRetry(Instant requestedFrom) {
        retrySchedule = executor.schedule(RETRY_DELAY, () -> retrieveOctopusPrices(requestedFrom));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiKey {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccountId {
    }
}
