package net.yudichev.jiotty.energy;

import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeter;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// The single source of truth for one user's Octopus energy provider. Acquires the per-credentials [OctopusAccountService] handle once, polls
/// [OctopusAccountService#getAccount] on a fixed interval through a [RetryableOperationExecutor], and feeds the result to two views off the same fetch:
///
/// - **prices** — derives the current tariff and region from each account update, routes to the cached app-scope Agile-prices instance for the tariff
/// (re-targeted on tariff change) and the AgilePredict instance for the region (re-targeted on region change), and combines them by preferring real
/// (already-published) values and appending predicted values for the period beyond. Emits [Failure.IncompatibleTariff] when the user switches to a non-Agile
/// tariff and [Failure.PriceRetrievalError] when the fetch fails.
/// - **account details** — publishes the trimmed [OctopusAccountDetails] to [#subscribeToAccountDetails] subscribers.
///
/// Consecutive identical fetch failures (same [HumanReadableExceptionMessage]) are deduplicated, so a run of identical failures produces a single
/// notification on each view regardless of how many retries or polls it spanned; a successful fetch resets the dedup state.
public final class OctopusEnergyProviderService extends BaseLifecycleComponent implements EnergyProviderService {
    private static final Logger logger = LogManager.getLogger(OctopusEnergyProviderService.class);
    private static final Duration ACCOUNT_POLL_INTERVAL = Duration.ofHours(12);
    private static final String AGILE_PRODUCT_CODE_PREFIX = "AGILE-";

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusEnergy octopusEnergy;
    private final String accountId;
    private final String apiKey;
    private final RetryableOperationExecutor retryExecutor;
    private final OctopusAgilePriceServiceRegistry octopusRegistry;
    private final AgilePredictPriceServiceRegistry agilePredictRegistry;
    /// The latest price-or-failure result, empty until the first one is produced. New price subscribers receive the present value immediately.
    private final ObservableValue<Optional<Either<Prices, Failure>>> priceResult = ObservableValue.concurrent(Optional.empty());
    /// The latest account-fetch outcome, empty until the first one is produced. New account-details subscribers receive the present value immediately.
    private final ObservableValue<Optional<AccountFetchResult>> accountResult = ObservableValue.concurrent(Optional.empty());

    private SchedulingExecutor executor;
    private OctopusAccountService accountService;
    private @Nullable Closeable pollSchedule;
    private @Nullable Closeable octopusSubscription;
    private @Nullable Closeable agilePredictSubscription;
    /// The [HumanReadableExceptionMessage#humanReadableMessage(Throwable)] of the last published fetch failure, cleared on a successful fetch. Consecutive
    /// failures with the same message are suppressed so a single outage produces one notification on each view. Accessed only on the poll executor.
    private @Nullable String lastPublishedFailureMessage;
    private @Nullable String currentTariffCode;
    private @Nullable Character currentRegion;
    private @Nullable Either<Prices, Failure> currentOctopusResult;
    private @Nullable Prices currentAgilePredictPrices;

    @Inject
    public OctopusEnergyProviderService(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        OctopusEnergy octopusEnergy,
                                        @AccountId String accountId,
                                        @ApiKey String apiKey,
                                        RetryableOperationExecutor retryExecutor,
                                        OctopusAgilePriceServiceRegistry octopusRegistry,
                                        AgilePredictPriceServiceRegistry agilePredictRegistry) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountId = checkNotNull(accountId);
        this.apiKey = checkNotNull(apiKey);
        this.retryExecutor = checkNotNull(retryExecutor);
        this.octopusRegistry = checkNotNull(octopusRegistry);
        this.agilePredictRegistry = checkNotNull(agilePredictRegistry);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountService = octopusEnergy.account(accountId, apiKey);
        pollSchedule = executor.scheduleAtFixedRate(Duration.ZERO, ACCOUNT_POLL_INTERVAL, this::poll);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, pollSchedule, octopusSubscription, agilePredictSubscription, accountService);
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
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return whenStartedAndNotLifecycling(() -> accountService.subscribeToAuthState(consumer));
    }

    @Override
    public Closeable subscribeToAccountDetails(Consumer<AccountFetchResult> consumer) {
        return whenStartedAndNotLifecycling(() -> accountResult.subscribe(result -> result.ifPresent(consumer)));
    }

    private void poll() {
        logger.debug("Polling Octopus account");
        // backoffEventConsumer fires on each retry; the final whenComplete fires on retry exhaustion. Both route through publishFailed, which deduplicates
        // identical consecutive messages. Marshal onto this service's executor because RetryableOperationExecutor invokes the consumer on whichever thread
        // completed the failed attempt.
        retryExecutor
                .withBackOffAndRetry("octopusAccount.getAccount",
                                     accountService::getAccount,
                                     (_, throwable) -> executor.execute(() -> publishFailed(throwable)))
                .thenAcceptAsync(this::publishLoaded, executor)
                .whenCompleteAsync((_, throwable) -> {
                    if (throwable != null) {
                        logger.info("Failed to fetch Octopus account after retries", throwable);
                        publishFailed(throwable);
                    }
                }, executor);
    }

    private void publishLoaded(OctopusAccountData account) {
        lastPublishedFailureMessage = null;
        accountResult.accept(Optional.of(new AccountFetchResult.Loaded(toAccountDetails(account))));
        handleAccountResolution(account);
    }

    private void publishFailed(Throwable cause) {
        String message = humanReadableMessage(cause);
        if (Objects.equals(message, lastPublishedFailureMessage)) {
            return;
        }
        lastPublishedFailureMessage = message;
        accountResult.accept(Optional.of(new AccountFetchResult.Failed(cause)));
        emitRetrievalError(cause);
    }

    private static OctopusAccountDetails toAccountDetails(OctopusAccountData account) {
        List<OctopusAccountDetails.MeterPoint> meterPoints =
                account.properties().stream()
                       .flatMap(property -> property.electricityMeterPoints().stream())
                       .map(meterPoint -> new OctopusAccountDetails.MeterPoint(
                               meterPoint.mpan(),
                               meterPoint.meters().stream().map(ElectricityMeter::serialNumber).toList(),
                               meterPoint.tariffs().stream()
                                         .map(tariff -> new OctopusAccountDetails.TariffPeriod(tariff.tariffCode(),
                                                                                               tariff.validFrom(),
                                                                                               tariff.validTo()))
                                         .toList()))
                       .toList();
        return new OctopusAccountDetails(meterPoints);
    }

    private void handleAccountResolution(OctopusAccountData account) {
        Tariff tariff;
        try {
            tariff = extractCurrentTariff(account);
        } catch (RuntimeException e) {
            // Parsing the response is not a transient API issue; do not retry. Surface as PriceRetrievalError so subscribers see something went wrong.
            logger.info("Failed to extract current tariff from account response", e);
            emitRetrievalError(e);
            return;
        }
        String tariffCode = tariff.tariffCode();
        String productCode = tariffCode.substring(5, tariffCode.length() - 2);
        char regionLetter = tariffCode.charAt(tariffCode.length() - 1);

        if (!productCode.startsWith(AGILE_PRODUCT_CODE_PREFIX)) {
            handleIncompatibleTariff(tariffCode);
            return;
        }

        if (!Objects.equals(tariffCode, currentTariffCode)) {
            logger.info("Tariff changed: {} → {}; rerouting Octopus delegate", currentTariffCode, tariffCode);
            closeSafelyIfNotNull(logger, octopusSubscription);
            octopusSubscription = null;
            currentOctopusResult = null;
            EnergyPriceService octopusDelegate = octopusRegistry.forTariff(productCode, tariffCode);
            octopusSubscription = octopusDelegate.subscribeToPrices(result -> executor.execute(() -> onOctopusResult(result)));
            currentTariffCode = tariffCode;
        }

        if (currentRegion == null || currentRegion != regionLetter) {
            logger.info("Region changed: {} → {}; rerouting AgilePredict delegate", currentRegion, regionLetter);
            closeSafelyIfNotNull(logger, agilePredictSubscription);
            agilePredictSubscription = null;
            currentAgilePredictPrices = null;
            EnergyPriceService agilePredictDelegate = agilePredictRegistry.forRegion(regionLetter);
            agilePredictSubscription = agilePredictDelegate.subscribeToPrices(result -> executor.execute(() -> onAgilePredictResult(result)));
            currentRegion = regionLetter;
        }
    }

    private void handleIncompatibleTariff(String tariffCode) {
        if (Objects.equals(tariffCode, currentTariffCode)) {
            // already in this state, don't re-emit
            return;
        }
        logger.info("User moved to non-Agile tariff {}; closing delegates and emitting IncompatibleTariff", tariffCode);
        closeSafelyIfNotNull(logger, octopusSubscription, agilePredictSubscription);
        octopusSubscription = null;
        agilePredictSubscription = null;
        currentOctopusResult = null;
        currentAgilePredictPrices = null;
        currentTariffCode = tariffCode;
        currentRegion = null;
        Either<Prices, Failure> result = Either.right(new Failure.IncompatibleTariff(tariffCode));
        priceResult.accept(Optional.of(result));
    }

    private void onOctopusResult(Either<Prices, Failure> result) {
        currentOctopusResult = result;
        combineAndNotify();
    }

    private void onAgilePredictResult(Either<Prices, Failure> result) {
        // AgilePredict has no native failure modes today; ignore failure side per the prior RealAndPredictedPriceService convention.
        result.getLeft().ifPresent(prices -> {
            currentAgilePredictPrices = prices;
            combineAndNotify();
        });
    }

    /// Emits a [Failure.PriceRetrievalError] to price subscribers. Reached from [#publishFailed] (which already deduplicates identical consecutive fetch
    /// failures) and from the account-parse failure branch of [#handleAccountResolution].
    private void emitRetrievalError(Throwable throwable) {
        Either<Prices, Failure> result = Either.right(new Failure.PriceRetrievalError(throwable));
        priceResult.accept(Optional.of(result));
    }

    private void combineAndNotify() {
        if (currentOctopusResult == null) {
            return;
        }
        Either<Prices, Failure> combined = currentOctopusResult.mapLeft(
                octopus -> currentAgilePredictPrices == null ? octopus : PriceCombiner.combine(octopus, currentAgilePredictPrices));
        priceResult.accept(Optional.of(combined));
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
