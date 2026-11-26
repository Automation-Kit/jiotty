package net.yudichev.jiotty.energy.octopus.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.graph.server.BaseGraphBasedServer;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.common.observer.ConcurrentObservableValue;
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
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.AccountFetchResult;
import net.yudichev.jiotty.energy.octopus.EnergyProviderService;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.ForecastEnergyPriceService;
import net.yudichev.jiotty.energy.octopus.MeterPoint;
import net.yudichev.jiotty.energy.octopus.OctopusAccountDetails;
import net.yudichev.jiotty.energy.octopus.OctopusPriceSlots;
import net.yudichev.jiotty.energy.octopus.OctopusStreams;
import net.yudichev.jiotty.energy.octopus.OctopusTariffCode;
import net.yudichev.jiotty.energy.octopus.PriceResults;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Loaded;
import net.yudichev.jiotty.energy.octopus.provider.AccountState.Unavailable;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.Bindings.Rng;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.FilteringConsumer.filtering;
import static net.yudichev.jiotty.common.lang.MoreThrowables.findInCausalChain;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.Bindings.ForwardWindow;

/// The single source of truth for the prices and account details of one Octopus account. It runs a graph of the account ([AccountNode]), the agreements in
/// the forward window ([AgreementWindowNode]), where the prices of each tariff of those agreements come from ([TariffSourcesNode]), the prices of each
/// tariff ([TariffPricesNode]), the forecast that extends dynamic prices ([ForecastNode]), the [TariffTimeline] ([TariffTimelineNode]) and the prices
/// joined across the switches between the agreements ([JoinedPricesNode]). The forward window is the period ahead of now set with
/// [OctopusEnergyPriceServiceModule.Builder#setForwardWindow].
///
/// After each wave it publishes the [TariffTimeline], then the joined [Prices] or the failure published in their place, then the next refresh time, then
/// the trimmed [OctopusAccountDetails] to [#subscribeToAccountDetails] subscribers. Until a fetch has succeeded, each failed attempt publishes
/// [AccountFetchResult.Failed] and a [Failure.PriceRetrievalError]; consecutive identical failures (same [HumanReadableExceptionMessage]) are deduplicated,
/// so a run of identical failures produces a single notification regardless of how many retries or polls it spanned. Once a fetch has succeeded, a failed
/// one publishes nothing and is logged.
///
/// A panic of the graph is reported to the [TaskFailureReporter], and the rebuilt graph starts from the results that subscribers hold: it keeps the loaded
/// account and the [LatestForecasts], and calls Octopus at most as often as the poll and retry intervals allow, counting the calls of the graph before it.
final class OctopusEnergyProviderService extends BaseGraphBasedServer implements EnergyProviderService {
    private static final Logger logger = LogManager.getLogger(OctopusEnergyProviderService.class);

    public static final Duration DEFAULT_ACCOUNT_POLL_INTERVAL = Duration.ofHours(12);
    /// What a panic of the graph is reported to the [TaskFailureReporter] as.
    static final String GRAPH_TASK_NAME = "Running the Octopus provider graph";
    /// How far back [#latestConsumptionInstant] looks for the most recent published consumption slot. This bounds the search, not a lag assumption: it
    /// comfortably exceeds Octopus' worst-case publication delay (hours to ~48 h), so the true frontier is found whenever the meter has reported at all.
    private static final Duration CONSUMPTION_FRONTIER_PROBE_WINDOW = Duration.ofDays(14);

    private final OctopusEnergy octopusEnergy;
    private final Provider<OctopusAccountService> accountServiceProvider;
    private final TaskFailureReporter taskFailureReporter;
    /// Slot cache backing the `query*` methods, shared with every other reader of the same streams. Each query defines (idempotently) its [OctopusStreams]
    /// stream and reads the requested range, so a past slot is fetched from Octopus at most once across every caller and process restart.
    private final TimeSeriesCache cache;
    private final AccountNodeImpl.Factory accountNodeFactory;
    private final AgreementWindowNodeImpl.Factory agreementWindowNodeFactory;
    private final TariffSourcesNodeImpl.Factory tariffSourcesNodeFactory;
    private final ForecastNodeImpl.Factory forecastNodeFactory;
    private final JoinedPricesNodeImpl.Factory joinedPricesNodeFactory;
    /// The latest result, `null` until the first one is produced. Concurrent because [#getPrices()] reads it on the thread that calls [#getPrices()].
    private final ConcurrentObservableValue<@Nullable PriceResult> priceResult;
    /// When the joined [Prices] are next expected to reach further than they do now; `null` until a priced tariff has reported its next refresh time.
    private final ObservableValue<@Nullable Instant> nextRefreshTime = ObservableValue.simple(null);
    /// The agreements that overlap the forward window and how each is priced; `null` until every one of them has been resolved once.
    private final ObservableValue<@Nullable TariffTimeline> tariffTimeline = ObservableValue.simple(null);
    /// The latest account-fetch outcome, `null` until the first one is produced.
    private final ObservableValue<@Nullable AccountFetchResult> accountResult = ObservableValue.simple(null);
    /// Kept across the graphs that this server builds, so that the [AccountNode] of a graph rebuilt after a panic spaces its first poll from the latest one.
    private final FetchHistory accountFetchHistory = new FetchHistory();
    /// Kept across the graphs that this server builds, so that a graph rebuilt after a panic resolves a failed tariff again, and calls Octopus for it, at
    /// the earliest [OctopusPriceSlots#FAILURE_RETRY_DELAY] after the failure.
    private final ResolutionHistory resolutionHistory = new ResolutionHistory();
    /// Kept across the graphs that this server builds, so that the first prices of a graph rebuilt after a panic carry the forecast, which
    /// [ForecastEnergyPriceService] delivers only later.
    private final LatestForecasts latestForecasts = new LatestForecasts();

    private OctopusAccountService accountService;
    /// The [AccountState] of the account details last published to the subscribers of [#subscribeToAccountDetails]; `null` until the first are published.
    /// A graph rebuilt after a panic starts from it.
    private @Nullable AccountState publishedAccountState;
    /// The timeline last published to the subscribers of [#subscribeToTariffTimeline]; `null` until the first is published. A graph rebuilt after a panic
    /// starts from it.
    private @Nullable TariffTimeline publishedTimeline;
    /// The result last published to the subscribers of [#subscribeToPrices]; `null` until the first is published.
    private @Nullable PriceResult publishedResult;
    /// The refresh time last published to the subscribers of [#subscribeToNextRefreshTime]; `null` until the first is published.
    private @Nullable Instant publishedRefreshTime;
    /// Created in [#createNodes]; `null` until then, and from a panic until the graph is next created.
    private @Nullable PublishedNodes publishedNodes;

    /// @param forwardWindow how far ahead of now the agreements are joined; a non-positive value fails the construction
    @Inject
    public OctopusEnergyProviderService(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                        CurrentDateTimeProvider timeProvider,
                                        @Rng DoubleSupplier backoffRng,
                                        OctopusEnergy octopusEnergy,
                                        @Dependency Provider<OctopusAccountService> accountServiceProvider,
                                        @ForwardWindow Duration forwardWindow,
                                        TimeSeriesCache cache,
                                        TaskFailureReporter taskFailureReporter,
                                        AccountNodeImpl.Factory accountNodeFactory,
                                        AgreementWindowNodeImpl.Factory agreementWindowNodeFactory,
                                        TariffSourcesNodeImpl.Factory tariffSourcesNodeFactory,
                                        ForecastNodeImpl.Factory forecastNodeFactory,
                                        JoinedPricesNodeImpl.Factory joinedPricesNodeFactory) {
        super(executorProvider, timeProvider, backoffRng);
        checkArgument(forwardWindow.isPositive(), "forwardWindow must be positive: %s", forwardWindow);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountServiceProvider = checkNotNull(accountServiceProvider);
        this.cache = checkNotNull(cache);
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
        this.accountNodeFactory = checkNotNull(accountNodeFactory);
        this.agreementWindowNodeFactory = checkNotNull(agreementWindowNodeFactory);
        this.tariffSourcesNodeFactory = checkNotNull(tariffSourcesNodeFactory);
        this.forecastNodeFactory = checkNotNull(forecastNodeFactory);
        this.joinedPricesNodeFactory = checkNotNull(joinedPricesNodeFactory);
        priceResult = ObservableValue.concurrent(null, e -> taskFailureReporter.onTaskException("Delivering the Octopus prices", e));
    }

    @Override
    protected void doStart0() {
        accountService = accountServiceProvider.get();
    }

    /// Closes the graph and then the account service, in one task queued on the executor. The [AccountNode] of the graph cancels its running fetch as it
    /// closes. The teardown that [BaseGraphBasedServer] queues afterwards finds the graph already closed.
    @Override
    protected void doStop0() {
        OctopusAccountService accountService = this.accountService;
        executor().execute(name(), () -> {
            stopSync();
            closeSafelyIfNotNull(logger, accountService);
        });
    }

    @Override
    public Optional<PriceResult> getPrices() {
        // Approved poll: EnergyPriceService.getPrices().
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(priceResult.get()));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(filtering(consumer, Objects::nonNull)));
    }

    @Override
    public Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
        return whenStartedAndNotLifecycling(() -> nextRefreshTime.subscribe(executor(), filtering(consumer, Objects::nonNull)));
    }

    @Override
    public Closeable subscribeToTariffTimeline(Consumer<TariffTimeline> consumer) {
        return whenStartedAndNotLifecycling(() -> tariffTimeline.subscribe(executor(), filtering(consumer, Objects::nonNull)));
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return whenStartedAndNotLifecycling(() -> accountService.subscribeToAuthState(consumer));
    }

    @Override
    public Closeable subscribeToAccountDetails(Consumer<AccountFetchResult> consumer) {
        return whenStartedAndNotLifecycling(() -> accountResult.subscribe(executor(), filtering(consumer, Objects::nonNull)));
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
        // Direct, uncached connector call (NOT OctopusStreams): this determines the publication frontier, the precondition on which the slot cache relies, so
        // routing it through the cache would tombstone the very not-yet-published slots we're checking for. The probe window comfortably exceeds Octopus'
        // worst-case publication delay; it bounds the search for the latest slot, it is not a lag assumption.
        return whenStartedAndNotLifecycling(() -> {
            Instant now = timeProvider.currentInstant();
            return accountService.getConsumption(mpan, meterSerial, now.minus(CONSUMPTION_FRONTIER_PROBE_WINDOW), now)
                                 .thenApply(rows -> rows.stream().map(ConsumptionRow::intervalStart).max(Comparator.naturalOrder()));
        });
    }

    /// Whether the account poll retries `failure`: every failure except one with a [JsonProcessingException] in its causal chain, a response that arrived but
    /// could not be parsed.
    public static boolean isRetryablePollFailure(Throwable failure) {
        return findInCausalChain(failure, JsonProcessingException.class).isEmpty();
    }

    @Override
    protected void createNodes(GraphRunner graphRunner, NodeRegistrator registrator) {
        AccountNode accountNode = registrator.register(
                accountNodeFactory.create(graphRunner, accountService, accountFetchHistory, Optional.ofNullable(publishedAccountState)));
        AgreementWindowNode agreementWindowNode = registrator.register(agreementWindowNodeFactory.create(graphRunner, accountNode));
        TariffSourcesNode tariffSourcesNode = registrator.register(tariffSourcesNodeFactory.create(graphRunner, agreementWindowNode, resolutionHistory));
        TariffPricesNode tariffPricesNode = registrator.register(new TariffPricesNodeImpl(graphRunner, tariffSourcesNode));
        ForecastNode forecastNode = registrator.register(forecastNodeFactory.create(graphRunner, agreementWindowNode, tariffSourcesNode, latestForecasts));
        TariffTimelineNode tariffTimelineNode = registrator.register(new TariffTimelineNodeImpl(graphRunner,
                                                                                                agreementWindowNode,
                                                                                                tariffSourcesNode,
                                                                                                Optional.ofNullable(publishedTimeline)));
        JoinedPricesNode joinedPricesNode = registrator.register(joinedPricesNodeFactory.create(graphRunner,
                                                                                                accountNode,
                                                                                                agreementWindowNode,
                                                                                                tariffPricesNode,
                                                                                                forecastNode,
                                                                                                tariffTimelineNode));
        publishedNodes = new PublishedNodes(accountNode, tariffTimelineNode, joinedPricesNode);
    }

    /// Publishes the timeline before any prices built from it, then the result and the next refresh time, each unless subscribers already hold it, then the
    /// account details once per [AccountState]. Publishes nothing from a panic until the graph is next created.
    @Override
    protected void recordState() {
        PublishedNodes nodes = publishedNodes;
        if (nodes == null) {
            return;
        }
        TariffTimeline timeline = nodes.tariffTimelineNode().timeline();
        if (timeline != null && !timeline.equals(publishedTimeline)) {
            logger.info("Tariff timeline now {}", timeline);
            publishedTimeline = timeline;
            tariffTimeline.accept(timeline);
        }
        PriceResult result = nodes.joinedPricesNode().result();
        if (result != null && !PriceResults.isUnchanged(result, publishedResult)) {
            switch (result) {
                case Prices prices -> logger.info("Prices now from {} till {}", prices.profileStart(), prices.profileEnd());
                case Failure failure -> logger.info("Price failure: {}", failure);
            }
            publishedResult = result;
            priceResult.accept(result);
        }
        Instant refreshTime = nodes.joinedPricesNode().nextRefreshTime();
        if (refreshTime != null && !refreshTime.equals(publishedRefreshTime)) {
            publishedRefreshTime = refreshTime;
            nextRefreshTime.accept(refreshTime);
        }
        AccountState accountState = nodes.accountNode().state();
        if (accountState != null && accountState != publishedAccountState) {
            publishedAccountState = accountState;
            accountResult.accept(switch (accountState) {
                case Loaded loaded -> new AccountFetchResult.Loaded(toAccountDetails(loaded.account()));
                case Unavailable(Throwable cause) -> new AccountFetchResult.Failed(cause);
            });
        }
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

    @Override
    protected void handlePanic(@Nullable String message, @Nullable Throwable cause) {
        publishedNodes = null;
        taskFailureReporter.onTaskException(GRAPH_TASK_NAME, new IllegalStateException(message, cause));
    }

    /// Qualifies the [OctopusAccountService] provider. [#doStart0()] obtains a new instance from it on each start, since [#doStop0()] closes the previous one.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }

    /// The nodes of one graph whose values are published to subscribers after each wave.
    private record PublishedNodes(AccountNode accountNode, TariffTimelineNode tariffTimelineNode, JoinedPricesNode joinedPricesNode) {
    }
}
