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
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
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

/// User-scope view of the app-scope Agile + AgilePredict price services. Polls the user's [OctopusAccountService] every 12h to derive the current tariff and
/// region, then routes the work to:
///
/// - the cached app-scope Agile-prices instance for the current tariff (re-targeted on tariff change),
/// - the cached app-scope AgilePredict-prices instance for the current region (re-targeted on region change).
///
/// Combines outputs by preferring real (already-published) values and appending predicted values for the period beyond. Emits [Failure.IncompatibleTariff] when
/// the user switches to a non-Agile tariff. The account fetch is wrapped in a [RetryableOperationExecutor] so transient Octopus outages or network glitches are
/// retried with exponential backoff (capped at ≈6h, half the poll interval). Every backoff event and the final retry-exhaustion failure surface to subscribers
/// as [Failure.PriceRetrievalError]; identical consecutive failures (same [HumanReadableExceptionMessage]) are silently deduplicated, so subscribers see one
/// notification per outage. [#subscribeToAuthState] forwards to the user's [OctopusAccountService] (the per-user API key is required for the account fetch, so
/// this part remains user-scope).
public final class OctopusAgileEnergyPriceServiceResolver extends BaseLifecycleComponent implements EnergyPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgileEnergyPriceServiceResolver.class);
    private static final Duration TARIFF_POLL_INTERVAL = Duration.ofHours(12);
    private static final String AGILE_PRODUCT_CODE_PREFIX = "AGILE-";

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusEnergy octopusEnergy;
    private final String accountId;
    private final String apiKey;
    private final OctopusAgilePriceServiceRegistry octopusRegistry;
    private final AgilePredictPriceServiceRegistry agilePredictRegistry;
    private final RetryableOperationExecutor retryExecutor;
    private final Listeners<Either<Prices, Failure>> listeners = new Listeners<>();

    private SchedulingExecutor executor;
    private OctopusAccountService accountService;
    private Closeable pollSchedule;
    private @Nullable Closeable octopusSubscription;
    private @Nullable Closeable agilePredictSubscription;
    private @Nullable String currentTariffCode;
    private @Nullable Character currentRegion;
    private @Nullable Either<Prices, Failure> currentOctopusResult;
    private @Nullable Prices currentAgilePredictPrices;
    private @Nullable
    volatile Either<Prices, Failure> lastResult;
    /// The [HumanReadableExceptionMessage#humanReadableMessage(Throwable)] of the last [Failure.PriceRetrievalError] emitted, cleared on successful resolution.
    /// Used to deduplicate identical consecutive failures so a single Octopus outage produces one notification, regardless of how many retries it triggered.
    private @Nullable String lastRetrievalErrorMessage;

    @Inject
    public OctopusAgileEnergyPriceServiceResolver(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                                  CurrentDateTimeProvider timeProvider,
                                                  OctopusEnergy octopusEnergy,
                                                  @AccountId String accountId,
                                                  @ApiKey String apiKey,
                                                  OctopusAgilePriceServiceRegistry octopusRegistry,
                                                  AgilePredictPriceServiceRegistry agilePredictRegistry,
                                                  RetryableOperationExecutor retryExecutor) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountId = checkNotNull(accountId);
        this.apiKey = checkNotNull(apiKey);
        this.octopusRegistry = checkNotNull(octopusRegistry);
        this.agilePredictRegistry = checkNotNull(agilePredictRegistry);
        this.retryExecutor = checkNotNull(retryExecutor);
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
        return whenStartedAndNotLifecycling(() -> accountService.subscribeToAuthState(consumer));
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountService = octopusEnergy.account(accountId, apiKey);
        pollSchedule = executor.scheduleAtFixedRate(Duration.ZERO, TARIFF_POLL_INTERVAL, this::pollTariff);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, pollSchedule, octopusSubscription, agilePredictSubscription, accountService);
    }

    private void pollTariff() {
        logger.debug("Polling user's tariff");
        // backoffEventConsumer fires on each retry — surface the in-flight transient failure to subscribers with anti-spam dedup. Marshal onto the resolver's
        // executor because RetryableOperationExecutor invokes the consumer on whichever thread completes the failed attempt (typically the HTTP client's).
        retryExecutor
                .withBackOffAndRetry("octopusAccount.getAccount",
                                     accountService::getAccount,
                                     (_, throwable) -> executor.submit(() -> emitRetrievalErrorIfNew(throwable)))
                .thenAcceptAsync(this::handleAccountResolution, executor)
                .whenCompleteAsync((_, throwable) -> {
                    if (throwable != null) {
                        // Retries exhausted (maxElapsedTime reached). Emit a final PriceRetrievalError; anti-spam will silence it if the message matches the
                        // last backoff-event emission, so subscribers see one notification per outage rather than one per attempt.
                        logger.info("Failed to resolve user's tariff after retries", throwable);
                        emitRetrievalErrorIfNew(throwable);
                    }
                }, executor);
    }

    private void handleAccountResolution(OctopusAccountData account) {
        Tariff tariff;
        try {
            tariff = extractCurrentTariff(account);
        } catch (RuntimeException e) {
            // Parsing the response is not a transient API issue; do not retry. Surface as PriceRetrievalError so subscribers see something went wrong.
            logger.info("Failed to extract current tariff from account response", e);
            emitRetrievalErrorIfNew(e);
            return;
        }
        // Successful resolution — reset the anti-spam state so a future outage re-notifies even if the message text happens to match an earlier one.
        lastRetrievalErrorMessage = null;
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
            octopusSubscription = octopusDelegate.subscribeToPrices(result -> executor.submit(() -> onOctopusResult(result)));
            currentTariffCode = tariffCode;
        }

        if (currentRegion == null || currentRegion != regionLetter) {
            logger.info("Region changed: {} → {}; rerouting AgilePredict delegate", currentRegion, regionLetter);
            closeSafelyIfNotNull(logger, agilePredictSubscription);
            agilePredictSubscription = null;
            currentAgilePredictPrices = null;
            EnergyPriceService agilePredictDelegate = agilePredictRegistry.forRegion(regionLetter);
            agilePredictSubscription = agilePredictDelegate.subscribeToPrices(result -> executor.submit(() -> onAgilePredictResult(result)));
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
        // IncompatibleTariff is a successful resolution (the user is reachable, their tariff is just not Agile). Reset the retrieval-error anti-spam.
        lastRetrievalErrorMessage = null;
        Either<Prices, Failure> result = Either.right(new Failure.IncompatibleTariff(tariffCode));
        lastResult = result;
        listeners.notify(result);
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

    /// Emits a [Failure.PriceRetrievalError] to subscribers if its human-readable message differs from the last one we emitted. Suppresses spam during long
    /// outages where every retry produces the same exception text. The anti-spam state is reset on successful tariff resolution and on
    /// [Failure.IncompatibleTariff] transitions, so future distinct failures still re-notify.
    private void emitRetrievalErrorIfNew(Throwable throwable) {
        String message = humanReadableMessage(throwable);
        if (Objects.equals(message, lastRetrievalErrorMessage)) {
            return;
        }
        lastRetrievalErrorMessage = message;
        Either<Prices, Failure> result = Either.right(new Failure.PriceRetrievalError(throwable));
        lastResult = result;
        listeners.notify(result);
    }

    private void combineAndNotify() {
        if (currentOctopusResult == null) {
            return;
        }
        // A successful Octopus result (Either.left) resets the retrieval-error anti-spam so the next failure re-notifies.
        currentOctopusResult.getLeft().ifPresent(_ -> lastRetrievalErrorMessage = null);
        Either<Prices, Failure> combined = currentOctopusResult.mapLeft(
                octopus -> currentAgilePredictPrices == null ? octopus : PriceCombiner.combine(octopus, currentAgilePredictPrices));
        lastResult = combined;
        listeners.notify(combined);
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
