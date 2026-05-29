package net.yudichev.jiotty.energy;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// Account-specific view on the Agile + AgilePredict price services. Subscribes to [OctopusAccountContext] for the account details, derives the current tariff
/// and region from each update, then routes the work to:
///
/// - the cached app-scope Agile-prices instance for the current tariff (re-targeted on tariff change),
/// - the cached app-scope AgilePredict-prices instance for the current region (re-targeted on region change).
///
/// Combines outputs by preferring real (already-published) values and appending predicted values for the period beyond. Emits [Failure.IncompatibleTariff] when
/// the user switches to a non-Agile tariff. [OctopusAccountContext] retries the account fetch and deduplicates identical consecutive failures, publishing each
/// distinct one as [AccountFetchResult.Failed]; this resolver surfaces each as [Failure.PriceRetrievalError]. [#subscribeToAuthState] forwards to the context.
public final class OctopusAgileEnergyPriceServiceResolver extends BaseLifecycleComponent implements EnergyPriceService {
    private static final Logger logger = LogManager.getLogger(OctopusAgileEnergyPriceServiceResolver.class);
    private static final String AGILE_PRODUCT_CODE_PREFIX = "AGILE-";

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusAccountContext accountContext;
    private final OctopusAgilePriceServiceRegistry octopusRegistry;
    private final AgilePredictPriceServiceRegistry agilePredictRegistry;
    private final Listeners<Either<Prices, Failure>> listeners = new Listeners<>();

    private SchedulingExecutor executor;
    private @Nullable Closeable accountSubscription;
    private @Nullable Closeable octopusSubscription;
    private @Nullable Closeable agilePredictSubscription;
    private @Nullable String currentTariffCode;
    private @Nullable Character currentRegion;
    private @Nullable Either<Prices, Failure> currentOctopusResult;
    private @Nullable Prices currentAgilePredictPrices;
    private @Nullable
    volatile Either<Prices, Failure> lastResult;

    @Inject
    public OctopusAgileEnergyPriceServiceResolver(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                                  CurrentDateTimeProvider timeProvider,
                                                  OctopusAccountContext accountContext,
                                                  OctopusAgilePriceServiceRegistry octopusRegistry,
                                                  AgilePredictPriceServiceRegistry agilePredictRegistry) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.accountContext = checkNotNull(accountContext);
        this.octopusRegistry = checkNotNull(octopusRegistry);
        this.agilePredictRegistry = checkNotNull(agilePredictRegistry);
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
        return whenStartedAndNotLifecycling(() -> accountContext.subscribeToAuthState(consumer));
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountSubscription = accountContext.accountDetails().subscribe(result -> executor.submit(() -> onAccountResult(result)));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, accountSubscription, octopusSubscription, agilePredictSubscription);
    }

    private void onAccountResult(AccountFetchResult result) {
        switch (result) {
            case AccountFetchResult.Loading _ -> { /* no fetch completed yet — nothing to route */ }
            case AccountFetchResult.Loaded loaded -> handleAccountResolution(loaded.account());
            case AccountFetchResult.Failed failed -> emitRetrievalError(failed.cause());
        }
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

    /// Emits a [Failure.PriceRetrievalError] to subscribers. [OctopusAccountContext] already deduplicates identical consecutive fetch failures upstream, so
    /// this maps each distinct one straight through; the rare account-parse failure (a successfully-fetched but unusable account) is surfaced the same way.
    private void emitRetrievalError(Throwable throwable) {
        Either<Prices, Failure> result = Either.right(new Failure.PriceRetrievalError(throwable));
        lastResult = result;
        listeners.notify(result);
    }

    private void combineAndNotify() {
        if (currentOctopusResult == null) {
            return;
        }
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
}
