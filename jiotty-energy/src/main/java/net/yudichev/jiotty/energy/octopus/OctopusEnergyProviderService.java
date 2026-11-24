package net.yudichev.jiotty.energy.octopus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.common.observer.ObservableValue;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.AccountProperty;
import net.yudichev.jiotty.connector.octopusenergy.ConsumptionRow;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeter;
import net.yudichev.jiotty.connector.octopusenergy.ElectricityMeterPoint;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.Product;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.StandingCharge;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffPeriod;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.AgreementPrices.AgreementInWindow;
import net.yudichev.jiotty.energy.octopus.AgreementPrices.JoinedPrices;
import net.yudichev.jiotty.energy.octopus.AgreementPrices.TariffPriceState;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.findInCausalChain;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;
import static net.yudichev.jiotty.energy.octopus.OctopusPriceSlots.FAILURE_RETRY_DELAY;

/// The single source of truth for one user's Octopus energy provider. Acquires the account's [OctopusAccountService] handle once per start, polls
/// [OctopusAccountService#getAccount] on a fixed interval through a [RetryableOperationExecutor], and feeds the result to two views off the same fetch:
///
/// - **prices**: takes the agreements of the account's import meter point that overlap the forward window, which is the period ahead of now set with
/// [OctopusEnergyPriceServiceModule.Builder#setForwardWindow]. It resolves the tariff of each through the [OctopusTariffPriceServiceRegistry] shared by every
/// account, and subscribes to the price service of each priced tariff and, while an Agile agreement is among them, to the forecast of its region. The
/// prices of those agreements are joined into one [Prices] across the switches between them. A scheduled task joins them again at the next start or end of
/// an agreement, and when a later agreement starts to overlap the forward window, so the result follows a switch without waiting for the next poll. A tariff
/// that failed to resolve is resolved again after a fixed delay. The [TariffTimeline] of those agreements is published before any prices built from it.
/// - **account details**: publishes the trimmed [OctopusAccountDetails] to [#subscribeToAccountDetails] subscribers.
///
/// Until a fetch has succeeded, each failed attempt publishes [AccountFetchResult.Failed] and a [Failure.PriceRetrievalError]; consecutive identical
/// failures (same [HumanReadableExceptionMessage]) are deduplicated, so a run of identical failures produces a single notification regardless of how many
/// retries or polls it spanned. Once a fetch has succeeded, a failed one publishes nothing and is logged. A poll does not fetch while the previous fetch is
/// still running or retrying.
public final class OctopusEnergyProviderService extends BaseLifecycleComponent implements EnergyProviderService {
    private static final Logger logger = LogManager.getLogger(OctopusEnergyProviderService.class);
    static final Duration DEFAULT_ACCOUNT_POLL_INTERVAL = Duration.ofHours(12);
    private static final Comparator<Tariff> BY_VALID_FROM = Comparator.comparing(Tariff::validFrom);
    /// How far back [#latestConsumptionInstant] looks for the most recent published consumption slot. This bounds the search, not a lag assumption: it
    /// comfortably exceeds Octopus' worst-case publication delay (hours to ~48 h), so the true frontier is found whenever the meter has reported at all.
    private static final Duration CONSUMPTION_FRONTIER_PROBE_WINDOW = Duration.ofDays(14);

    private final Provider<SchedulingExecutor> executorProvider;
    private final CurrentDateTimeProvider timeProvider;
    private final OctopusEnergy octopusEnergy;
    private final Provider<OctopusAccountService> accountServiceProvider;
    private final Duration accountPollInterval;
    private final Duration forwardWindow;
    private final RetryableOperationExecutor retryExecutor;
    private final OctopusTariffPriceServiceRegistry tariffRegistry;
    private final PriceForecastServiceRegistry priceForecastRegistry;
    /// App-scoped slot cache backing the `query*` methods. Each query defines (idempotently) its [OctopusStreams] stream and reads the requested range, so a
    /// past slot is fetched from Octopus at most once across every caller and process restart.
    private final TimeSeriesCache cache;
    /// The latest result, `null` until the first one is produced. New price subscribers receive the present value immediately.
    private final ObservableValue<@Nullable PriceResult> priceResult;
    /// When the joined [Prices] are next expected to reach further than they do now; `null` until a priced tariff has reported its next refresh time.
    private final ObservableValue<@Nullable Instant> nextRefreshTime = ObservableValue.simple(null);
    /// The agreements that overlap the forward window and how each is priced; `null` until every one of them has been resolved once.
    private final ObservableValue<@Nullable TariffTimeline> tariffTimeline = ObservableValue.simple(null);
    /// The latest account-fetch outcome, empty until the first one is produced. New account-details subscribers receive the present value immediately.
    private final ObservableValue<Optional<AccountFetchResult>> accountResult;
    /// The [TariffState] of each tariff in the agreements that overlap the forward window.
    private final Map<String, TariffState> tariffStatesByTariffCode = new HashMap<>();

    private SchedulingExecutor executor;
    private OctopusAccountService accountService;
    private @Nullable Closeable pollSchedule;
    /// The account fetch that is still running or retrying, `null` while none is. Cancelling it stops its retries, each of which calls Octopus with the user's
    /// API key.
    private @Nullable CompletableFuture<OctopusAccountData> accountFetch;
    /// The agreements of the account's import meter point, as the latest fetch reported them.
    private ImmutableList<Tariff> agreements = ImmutableList.of();
    /// The agreements of the latest fetch that overlapped the forward window at the latest [#rejoin()], in time order.
    private ImmutableList<Tariff> agreementsInWindow = ImmutableList.of();
    private @Nullable Closeable forecastSubscription;
    private @Nullable Character forecastRegion;
    private @Nullable Prices forecastPrices;
    private @Nullable Closeable boundarySchedule;
    private @Nullable Closeable resolutionRetrySchedule;
    /// The [HumanReadableExceptionMessage#humanReadableMessage(Throwable)] of the last published fetch failure. Consecutive failures with the same message
    /// are suppressed so a single outage produces one notification on each view.
    private @Nullable String lastPublishedFailureMessage;
    /// Whether a fetched account has been routed to its price services; [#publishFailed(Throwable)] publishes nothing once one has.
    private boolean accountLoaded;

    @Inject
    public OctopusEnergyProviderService(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        OctopusEnergy octopusEnergy,
                                        @Dependency Provider<OctopusAccountService> accountServiceProvider,
                                        @AccountPollInterval Duration accountPollInterval,
                                        @ForwardWindow Duration forwardWindow,
                                        @PollRetry RetryableOperationExecutor retryExecutor,
                                        OctopusTariffPriceServiceRegistry tariffRegistry,
                                        PriceForecastServiceRegistry priceForecastRegistry,
                                        TimeSeriesCache cache,
                                        TaskFailureReporter taskFailureReporter) {
        checkNotNull(taskFailureReporter);
        priceResult = ObservableValue.concurrent(null, e -> taskFailureReporter.onTaskException("Delivering the Octopus prices", e));
        accountResult = ObservableValue.concurrent(Optional.empty(), e -> taskFailureReporter.onTaskException("Delivering the Octopus account details", e));
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountServiceProvider = checkNotNull(accountServiceProvider);
        this.accountPollInterval = checkNotNull(accountPollInterval);
        checkArgument(forwardWindow.isPositive(), "forwardWindow must be positive: %s", forwardWindow);
        this.forwardWindow = forwardWindow;
        this.retryExecutor = checkNotNull(retryExecutor);
        this.tariffRegistry = checkNotNull(tariffRegistry);
        this.priceForecastRegistry = checkNotNull(priceForecastRegistry);
        this.cache = checkNotNull(cache);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountService = accountServiceProvider.get();
        pollSchedule = executor.scheduleAtFixedRate(Duration.ZERO, accountPollInterval, this::poll);
    }

    /// Marshals the teardown onto the executor: a task that passed [#ifNotStopped(Runnable)] just before this stop may still be writing the state that the
    /// teardown closes.
    @Override
    protected void doStop() {
        Closeable pollSchedule = this.pollSchedule;
        OctopusAccountService accountService = this.accountService;
        executor.execute(name(), () -> closeSafelyIfNotNull(logger,
                                                            resolutionRetrySchedule,
                                                            boundarySchedule,
                                                            forecastSubscription,
                                                            () -> closeSafelyIfNotNull(logger, tariffStatesByTariffCode.values()),
                                                            this::cancelAccountFetch,
                                                            pollSchedule,
                                                            accountService));
    }

    private void cancelAccountFetch() {
        if (accountFetch != null) {
            accountFetch.cancel(false);
        }
    }

    @Override
    public Optional<PriceResult> getPrices() {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(priceResult.get()));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(result -> {
            if (result != null) {
                consumer.accept(result);
            }
        }));
    }

    @Override
    public Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
        return subscribeToNonNull(nextRefreshTime, consumer);
    }

    @Override
    public Closeable subscribeToTariffTimeline(Consumer<TariffTimeline> consumer) {
        return subscribeToNonNull(tariffTimeline, consumer);
    }

    /// Subscribes `consumer` to every value of `value` but `null`, delivered on the executor.
    private <T> Closeable subscribeToNonNull(ObservableValue<@Nullable T> value, Consumer<T> consumer) {
        return whenStartedAndNotLifecycling(() -> value.subscribe(executor, element -> {
            if (element != null) {
                consumer.accept(element);
            }
        }));
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return whenStartedAndNotLifecycling(() -> accountService.subscribeToAuthState(consumer));
    }

    @Override
    public Closeable subscribeToAccountDetails(Consumer<AccountFetchResult> consumer) {
        return whenStartedAndNotLifecycling(() -> accountResult.subscribe(result -> result.ifPresent(consumer)));
    }

    @Override
    public CompletableFuture<ImmutableMap<Instant, ConsumptionRow>> queryConsumption(String userId, String mpan, String meterSerial, Instant from, Instant to) {
        return whenStartedAndNotLifecycling(() -> OctopusStreams.consumptionStream(cache, accountService, userId, mpan, meterSerial).readRange(from, to));
    }

    @Override
    public CompletableFuture<ImmutableMap<Instant, StandardUnitRate>> queryRates(String productCode, String tariffCode, Instant from, Instant to) {
        return whenStartedAndNotLifecycling(
                () -> OctopusStreams.ratesStream(cache, regionService(tariffCode), timeProvider, productCode, tariffCode).readRange(from, to));
    }

    @Override
    public CompletableFuture<ImmutableMap<Instant, StandingCharge>> queryStandingCharges(String productCode, String tariffCode, Instant from, Instant to) {
        return whenStartedAndNotLifecycling(
                () -> OctopusStreams.standingChargesStream(cache, regionService(tariffCode), timeProvider, productCode, tariffCode).readRange(from, to));
    }

    private OctopusRegionService regionService(String tariffCode) {
        return octopusEnergy.region(OctopusTariffCode.region(tariffCode));
    }

    @Override
    public CompletableFuture<List<Product>> queryProducts(Instant availableAt) {
        return whenStartedAndNotLifecycling(() -> octopusEnergy.listProducts(availableAt));
    }

    @Override
    public CompletableFuture<ProductDetails> queryProductDetails(String code) {
        return whenStartedAndNotLifecycling(() -> octopusEnergy.getProductDetails(code));
    }

    @Override
    public CompletableFuture<Optional<Instant>> latestConsumptionInstant(String mpan, String meterSerial) {
        // Direct, uncached connector call (NOT OctopusStreams): this determines the publication frontier, the precondition the slot cache relies on, so routing
        // it through the cache would tombstone the very not-yet-published slots we're checking for. The probe window comfortably exceeds Octopus' worst-case
        // publication delay; it bounds the search for the latest slot, it is not a lag assumption.
        return whenStartedAndNotLifecycling(() -> {
            Instant now = timeProvider.currentInstant();
            return accountService.getConsumption(mpan, meterSerial, now.minus(CONSUMPTION_FRONTIER_PROBE_WINDOW), now)
                                 .thenApply(rows -> rows.stream().map(ConsumptionRow::intervalStart).max(Comparator.naturalOrder()));
        });
    }

    /// Whether the account poll retries `failure`: every failure except one with a [JsonProcessingException] in its causal chain, a response that arrived but
    /// could not be parsed.
    static boolean isRetryablePollFailure(Throwable failure) {
        return findInCausalChain(failure, JsonProcessingException.class).isEmpty();
    }

    private void poll() {
        if (accountFetch != null) {
            logger.debug("Skipping the Octopus account poll: the previous fetch is still retrying");
            return;
        }
        logger.debug("Polling Octopus account");
        // backoffEventConsumer fires on each retry; the final whenComplete fires on retry exhaustion. Both route through publishFailed, which deduplicates
        // identical consecutive messages. Marshal onto this service's executor because RetryableOperationExecutor invokes the consumer on whichever thread
        // completed the failed attempt.
        CompletableFuture<OctopusAccountData> fetch = retryExecutor.withBackOffAndRetry(
                "octopusAccount.getAccount",
                accountService::getAccount,
                (_, throwable) -> executor.tryExecute("publishFailed", () -> ifNotStopped(() -> publishFailed(throwable))));
        accountFetch = fetch;
        fetch.thenAcceptAsync(account -> ifNotStopped(() -> publishLoaded(account)), executor)
             .whenCompleteAsync((_, throwable) -> ifNotStopped(() -> {
                 accountFetch = null;
                 if (throwable != null) {
                     logger.info("Failed to fetch Octopus account after retries", throwable);
                     publishFailed(throwable);
                 }
             }), executor);
    }

    private void publishLoaded(OctopusAccountData account) {
        agreements = ImmutableList.sortedCopyOf(BY_VALID_FROM, importAgreements(account));
        rejoin();
        // Published and set only once the account has been routed, so that a routing failure on the first load reaches publishFailed as a fetch failure does.
        accountResult.accept(Optional.of(new AccountFetchResult.Loaded(toAccountDetails(account))));
        accountLoaded = true;
    }

    private void publishFailed(Throwable cause) {
        if (accountLoaded) {
            // The prices come from the tariffs' price services and the poll only detects a change of agreements or meter, so a failed refresh leaves the
            // published prices and account in place.
            return;
        }
        String message = humanReadableMessage(cause);
        if (Objects.equals(message, lastPublishedFailureMessage)) {
            return;
        }
        lastPublishedFailureMessage = message;
        accountResult.accept(Optional.of(new AccountFetchResult.Failed(cause)));
        publishPricesIfChanged(new Failure.PriceRetrievalError(cause));
    }

    private static OctopusAccountDetails toAccountDetails(OctopusAccountData account) {
        ImmutableList.Builder<MeterPoint> meterPoints = ImmutableList.builder();
        for (AccountProperty property : account.properties()) {
            for (ElectricityMeterPoint meterPoint : property.electricityMeterPoints()) {
                MeterPoint.Builder builder = MeterPoint.builder()
                                                       .setMpan(meterPoint.mpan())
                                                       .setTariffs(meterPoint.tariffs())
                                                       .setExport(meterPoint.isExport());
                for (ElectricityMeter meter : meterPoint.meters()) {
                    builder.addMeterSerials(meter.serialNumber());
                }
                meterPoints.add(builder.build());
            }
        }
        return new OctopusAccountDetails(meterPoints.build());
    }

    /// @return the agreements of the first property's first import meter point, or none when the account has no such point. An export point is skipped,
    ///         because it carries the export tariff, not the tariff that the user pays for consumption.
    private static List<Tariff> importAgreements(OctopusAccountData account) {
        if (account.properties().isEmpty()) {
            return ImmutableList.of();
        }
        for (ElectricityMeterPoint meterPoint : account.properties().getFirst().electricityMeterPoints()) {
            if (!meterPoint.isExport()) {
                return meterPoint.tariffs();
            }
        }
        return ImmutableList.of();
    }

    /// Joins the prices of the agreements that overlap the forward window:
    ///
    /// - closes the [TariffState] of each tariff that none of those agreements is on;
    /// - resolves each tariff of those agreements whose [TariffState#needsResolution()] holds;
    /// - calls [#updateForecastSubscription()], [#scheduleBoundary(Instant)] and [#publish()].
    private void rejoin() {
        Instant now = timeProvider.currentInstant();
        Instant windowEnd = now.plus(forwardWindow);
        ImmutableList.Builder<Tariff> overlappingAgreements = ImmutableList.builderWithExpectedSize(agreements.size());
        // An index of the codes, so that dropping the states of the tariffs that left the window costs one lookup per state
        Set<String> tariffCodesInWindow = HashSet.newHashSet(agreements.size());
        for (Tariff agreement : agreements) {
            if (agreement.overlaps(now, windowEnd)) {
                overlappingAgreements.add(agreement);
                tariffCodesInWindow.add(agreement.tariffCode());
            }
        }
        agreementsInWindow = overlappingAgreements.build();
        tariffStatesByTariffCode.entrySet().removeIf(entry -> {
            if (tariffCodesInWindow.contains(entry.getKey())) {
                return false;
            }
            logger.info("Tariff {} has left the price window", entry.getKey());
            closeSafelyIfNotNull(logger, entry.getValue());
            return true;
        });
        for (Tariff agreement : agreementsInWindow) {
            TariffState state = tariffStatesByTariffCode.computeIfAbsent(agreement.tariffCode(), _ -> new TariffState());
            if (state.needsResolution()) {
                resolve(agreement.tariffCode(), state);
            }
        }
        updateForecastSubscription();
        scheduleBoundary(now);
        publish();
    }

    private void resolve(String tariffCode, TariffState state) {
        CompletableFuture<TariffPriceSource> sourceFuture = tariffRegistry.forTariff(tariffCode);
        // Marked only once the registry has returned a future, so that a throw from forTariff leaves the tariff for the next rejoin to resolve.
        state.startResolving();
        sourceFuture.whenComplete((source, throwable) -> executor.tryExecute(
                "onTariffResolved",
                () -> ifNotStopped(() -> onTariffResolved(tariffCode, state, source, throwable))));
    }

    /// @param source    the tariff's price source, `null` when the resolution failed
    /// @param throwable the failure of the resolution, `null` when it succeeded
    private void onTariffResolved(String tariffCode, TariffState state, @Nullable TariffPriceSource source, @Nullable Throwable throwable) {
        if (tariffStatesByTariffCode.get(tariffCode) != state) {
            return;
        }
        if (throwable != null) {
            logger.info("Failed to resolve Octopus tariff {}, will retry in {}", tariffCode, FAILURE_RETRY_DELAY, throwable);
            state.onResolutionFailed(throwable);
            if (resolutionRetrySchedule == null) {
                resolutionRetrySchedule = executor.schedule(FAILURE_RETRY_DELAY, () -> {
                    resolutionRetrySchedule = null;
                    rejoin();
                });
            }
        } else {
            state.onResolved(checkNotNull(source));
            if (source instanceof TariffPriceSource.Priced(var service, _, _)) {
                state.setSubscriptions(service.subscribeToPrices(result -> executor.tryExecute(
                                               "onTariffPrices", () -> ifNotStopped(() -> onTariffUpdate(tariffCode, state, () -> state.onPrices(result))))),
                                       service.subscribeToNextRefreshTime(value -> executor.tryExecute(
                                               "onTariffRefreshTime",
                                               () -> ifNotStopped(() -> onTariffUpdate(tariffCode, state, () -> state.onNextRefreshTime(value))))));
            }
        }
        updateForecastSubscription();
        publish();
    }

    private void onTariffUpdate(String tariffCode, TariffState state, Runnable update) {
        if (tariffStatesByTariffCode.get(tariffCode) == state) {
            update.run();
            publish();
        }
    }

    /// Subscribes to the forecast for the first Agile agreement's supply region, or closes the subscription when no Agile agreement overlaps the forward
    /// window.
    private void updateForecastSubscription() {
        Character region = null;
        for (Tariff agreement : agreementsInWindow) {
            if (tariffStatesByTariffCode.get(agreement.tariffCode()).source() instanceof TariffPriceSource.Priced(_, var pricing, _) && pricing.isDynamic()) {
                region = OctopusTariffCode.region(agreement.tariffCode());
                break;
            }
        }
        if (Objects.equals(region, forecastRegion)) {
            return;
        }
        logger.info("Forecast region changed: {} → {}", forecastRegion, region);
        closeSafelyIfNotNull(logger, forecastSubscription);
        forecastSubscription = null;
        forecastPrices = null;
        forecastRegion = region;
        if (region != null) {
            forecastSubscription = priceForecastRegistry.forRegion(region).subscribeToPrices(
                    prices -> executor.tryExecute("onForecastPrices", () -> ifNotStopped(() -> onForecastPrices(prices))));
        }
    }

    private void onForecastPrices(Prices prices) {
        forecastPrices = prices;
        publish();
    }

    /// Schedules a join of the prices at the first of these instants after `now`, replacing any join scheduled before:
    ///
    /// - a start or end, inside the forward window, of an agreement that overlaps the window;
    /// - the first instant at which the window overlaps the first agreement that starts after it.
    private void scheduleBoundary(Instant now) {
        closeSafelyIfNotNull(logger, boundarySchedule);
        boundarySchedule = null;
        Instant windowEnd = now.plus(forwardWindow);
        Instant nextBoundary = null;
        for (Tariff agreement : agreementsInWindow) {
            nextBoundary = earliestInside(nextBoundary, agreement.validFrom(), now, windowEnd);
            nextBoundary = earliestInside(nextBoundary, agreement.validTo().orElse(null), now, windowEnd);
        }
        for (Tariff agreement : agreements) {
            if (!agreement.validFrom().isBefore(windowEnd)) {
                // The window [t, t + forwardWindow) overlaps the agreement from the first instant t after its start minus forwardWindow.
                Instant entry = agreement.validFrom().minus(forwardWindow).plusNanos(1);
                if (nextBoundary == null || entry.isBefore(nextBoundary)) {
                    nextBoundary = entry;
                }
                break;
            }
        }
        if (nextBoundary != null) {
            boundarySchedule = executor.schedule(Duration.between(now, nextBoundary), () -> {
                boundarySchedule = null;
                logger.info("An Octopus agreement starts, ends or enters the price window now; joining the prices again");
                rejoin();
            });
        }
    }

    /// @param earliest  the earliest boundary found so far, `null` when none has been
    /// @param candidate a start or end of an agreement, `null` for the end of an agreement with no end date
    /// @return the earlier of `earliest` and `candidate`, counting `candidate` only when it is after `now` and before `windowEnd`; `null` when neither is
    ///         such a boundary
    private static @Nullable Instant earliestInside(@Nullable Instant earliest, @Nullable Instant candidate, Instant now, Instant windowEnd) {
        if (candidate == null || !candidate.isAfter(now) || !candidate.isBefore(windowEnd)) {
            return earliest;
        }
        return earliest == null || candidate.isBefore(earliest) ? candidate : earliest;
    }

    /// Publishes the timeline once every agreement that overlaps the forward window is resolved, and only then the joined prices and next refresh time, so
    /// every price notification follows the timeline of its agreements. A failure is published at once, without waiting for the timeline.
    private void publish() {
        Instant now = timeProvider.currentInstant();
        Instant windowEnd = now.plus(forwardWindow);
        ImmutableList.Builder<TariffPeriod> periods = ImmutableList.builderWithExpectedSize(agreementsInWindow.size());
        ImmutableList.Builder<AgreementInWindow> agreementPrices = ImmutableList.builderWithExpectedSize(agreementsInWindow.size());
        boolean allResolved = true;
        for (Tariff agreement : agreementsInWindow) {
            TariffState state = tariffStatesByTariffCode.get(agreement.tariffCode());
            Instant endInWindow = agreement.validTo().filter(end -> end.isBefore(windowEnd)).orElse(windowEnd);
            agreementPrices.add(new AgreementInWindow(agreement.validFrom(), endInWindow, agreement.tariffCode(), state.toPriceState()));
            switch (state.source()) {
                case null -> allResolved = false;
                case TariffPriceSource.Priced(_, var pricing, var supplierManagedCharging) ->
                        periods.add(createPeriod(agreement, pricing, supplierManagedCharging));
                case TariffPriceSource.Unsupported _ -> periods.add(createPeriod(agreement, TariffPricing.UNSUPPORTED, false));
            }
        }
        if (allResolved) {
            TariffTimeline timeline = new TariffTimeline(periods.build());
            if (!timeline.equals(tariffTimeline.get())) {
                logger.info("Tariff timeline now {}", timeline);
                tariffTimeline.accept(timeline);
            }
        }
        JoinedPrices joinedPrices = AgreementPrices.join(agreementPrices.build(), now, windowEnd, Optional.ofNullable(forecastPrices));
        joinedPrices.result().ifPresent(result -> {
            if (result instanceof Failure || tariffTimeline.get() != null) {
                publishPricesIfChanged(result);
            }
        });
        joinedPrices.nextRefreshTime().ifPresent(value -> {
            if (!value.equals(nextRefreshTime.get())) {
                nextRefreshTime.accept(value);
            }
        });
    }

    private static TariffPeriod createPeriod(Tariff agreement, TariffPricing pricing, boolean supplierManagedCharging) {
        return TariffPeriod.builder()
                           .setFrom(agreement.validFrom())
                           .setTo(agreement.validTo())
                           .setPricing(pricing)
                           .setSupplierManagedCharging(supplierManagedCharging)
                           .build();
    }

    /// Publishes `result` unless [PriceResults#isUnchanged] holds for it and the published result.
    private void publishPricesIfChanged(PriceResult result) {
        if (PriceResults.isUnchanged(result, priceResult.get())) {
            return;
        }
        switch (result) {
            case Prices prices -> logger.info("Prices now from {} till {}", prices.profileStart(), prices.profileEnd());
            case Failure failure -> logger.info("Price failure: {}", failure);
        }
        priceResult.accept(result);
    }

    /// What is known of the tariff of an agreement that overlaps the forward window.
    private static final class TariffState implements Closeable {
        /// `null` until the tariff has been resolved
        private @Nullable TariffPriceSource source;
        private boolean resolving;
        /// `null` unless the latest resolution failed
        private @Nullable Throwable resolutionFailure;
        /// `null` unless the tariff is priced
        private @Nullable Closeable pricesSubscription;
        /// `null` unless the tariff is priced
        private @Nullable Closeable nextRefreshTimeSubscription;
        /// `null` until the tariff's price service has published
        private @Nullable PriceResult result;
        /// `null` until the tariff's price service has published its next refresh time
        private @Nullable Instant nextRefreshTime;

        public @Nullable TariffPriceSource source() {
            return source;
        }

        /// @return whether the tariff is neither resolved nor being resolved
        public boolean needsResolution() {
            return source == null && !resolving;
        }

        public void startResolving() {
            resolving = true;
            resolutionFailure = null;
        }

        public void onResolutionFailed(Throwable failure) {
            resolving = false;
            resolutionFailure = failure;
        }

        public void onResolved(TariffPriceSource resolvedSource) {
            resolving = false;
            source = resolvedSource;
        }

        public void setSubscriptions(Closeable newPricesSubscription, Closeable newNextRefreshTimeSubscription) {
            pricesSubscription = newPricesSubscription;
            nextRefreshTimeSubscription = newNextRefreshTimeSubscription;
        }

        public void onPrices(PriceResult newResult) {
            result = newResult;
        }

        public void onNextRefreshTime(Instant newNextRefreshTime) {
            nextRefreshTime = newNextRefreshTime;
        }

        public TariffPriceState toPriceState() {
            return switch (source) {
                case null -> resolutionFailure == null ? TariffPriceState.Resolving.INSTANCE : new TariffPriceState.ResolutionFailed(resolutionFailure);
                case TariffPriceSource.Unsupported _ -> TariffPriceState.Unsupported.INSTANCE;
                case TariffPriceSource.Priced(_, var pricing, _) ->
                        new TariffPriceState.Priced(Optional.ofNullable(result), Optional.ofNullable(nextRefreshTime), pricing.isDynamic());
            };
        }

        @Override
        public void close() {
            closeSafelyIfNotNull(logger, pricesSubscription, nextRefreshTimeSubscription);
        }
    }

    /// Qualifies the [OctopusAccountService] provider. [#doStart()] obtains a new instance from it on each start, since [#doStop()] closes the previous one.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    /// Qualifies the interval between account fetches: the value passed to [OctopusEnergyPriceServiceModule.Builder#withAccountPollInterval], or
    /// [#DEFAULT_ACCOUNT_POLL_INTERVAL] when that is not called.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccountPollInterval {
    }

    /// Qualifies the long-window [RetryableOperationExecutor] used by the background account poll (see [#retryExecutor]). Annotated so the binding never
    /// collides with, or accidentally captures, an unannotated executor of the same type in the parent injector.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface PollRetry {
    }
}
