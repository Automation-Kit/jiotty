package net.yudichev.jiotty.energy.octopus.tariff;

import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.graph.server.BaseGraphBasedServer;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.observer.ObservableValue;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.FetchHistory;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceService;
import net.yudichev.jiotty.energy.octopus.PriceResults;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.FilteringConsumer.filtering;

/// The base of the [OctopusTariffPriceService] of one tariff. It runs a graph of a [DailyRetrievalNode], a [PriceRetrievalNode] and a
/// [PricePublicationNode], and publishes the results after each wave of the graph. A panic of the graph is reported to the [TaskFailureReporter], and the
/// rebuilt graph starts from the results that subscribers hold.
///
/// @implSpec A subclass implements [TariffRetrieval], whose methods the nodes of the graph call to fetch the prices and to plan what follows each retrieval.
abstract class BaseOctopusTariffPriceService extends BaseGraphBasedServer implements OctopusTariffPriceService, TariffRetrieval {
    private static final Logger logger = LogManager.getLogger(BaseOctopusTariffPriceService.class);

    /// What a panic of the graph is reported to the [TaskFailureReporter] as, followed by [#tariffCode()].
    static final String GRAPH_TASK_NAME_PREFIX = "Running the Octopus price graph of tariff ";

    private final TaskFailureReporter taskFailureReporter;
    private final String tariffCode;
    /// The most recent result; `null` until the first one is produced.
    private final ObservableValue<@Nullable PriceResult> priceResult = ObservableValue.simple(null);
    /// When the prices are next expected to reach further than they do now; `null` until the first retrieval completes or fails.
    private final ObservableValue<@Nullable Instant> nextRefreshTime = ObservableValue.simple(null);
    /// Kept across the rebuilds of the graph after a panic, so that the first retrieval of each rebuilt graph is spaced from the latest retrieval of the
    /// graph before it, however often the graph panics.
    private final FetchHistory fetchHistory = new FetchHistory();
    /// Created in [#createNodes]; `null` until then, and from a panic until the graph is next created.
    private @Nullable PublishedNodes publishedNodes;
    /// The result last published to the subscribers of [#subscribeToPrices]; `null` until the first is published. A graph rebuilt after a panic starts from
    /// it.
    private @Nullable PriceResult publishedResult;
    /// The refresh time last published to the subscribers of [#subscribeToNextRefreshTime]; `null` until the first is published.
    private @Nullable Instant publishedRefreshTime;

    BaseOctopusTariffPriceService(Provider<SchedulingExecutor> executorProvider,
                                  CurrentDateTimeProvider timeProvider,
                                  DoubleSupplier backoffRng,
                                  TaskFailureReporter taskFailureReporter,
                                  String tariffCode) {
        super(executorProvider, timeProvider, backoffRng);
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
        this.tariffCode = checkNotNull(tariffCode);
    }

    @Override
    public final Closeable subscribeToPrices(Consumer<PriceResult> consumer) {
        return whenStartedAndNotLifecycling(() -> priceResult.subscribe(executor(), filtering(consumer, Objects::nonNull)));
    }

    @Override
    public final Closeable subscribeToNextRefreshTime(Consumer<Instant> consumer) {
        return whenStartedAndNotLifecycling(() -> nextRefreshTime.subscribe(executor(), filtering(consumer, Objects::nonNull)));
    }

    @Override
    protected final void createNodes(GraphRunner graphRunner, NodeRegistrator registrator) {
        DailyRetrievalNode dailyRetrievalNode = registrator.register(new DailyRetrievalNodeImpl(graphRunner, dailyRetrievalTime()));
        PriceRetrievalNode retrievalNode = registrator.register(new PriceRetrievalNodeImpl(graphRunner, this, dailyRetrievalNode, fetchHistory));
        PricePublicationNode publicationNode = registrator.register(
                new PricePublicationNodeImpl(graphRunner, this, dailyRetrievalNode, retrievalNode, Optional.ofNullable(publishedResult)));
        publishedNodes = new PublishedNodes(retrievalNode, publicationNode);
    }

    /// Publishes the result and the next refresh time, each unless subscribers already hold it, so a retrieval that returns the same prices again
    /// notifies no subscriber. Publishes nothing from a panic until the graph is next created.
    @Override
    protected final void recordState() {
        PublishedNodes publishedNodes = this.publishedNodes;
        if (publishedNodes == null) {
            return;
        }
        PriceResult result = publishedNodes.publicationNode().publishedResult();
        if (result != null && !PriceResults.isUnchanged(result, publishedResult)) {
            if (result instanceof Prices prices) {
                logger.info("[{}] Prices now from {} till {}", tariffCode, prices.profileStart(), prices.profileEnd());
            }
            publishedResult = result;
            priceResult.accept(result);
        }
        RetrievalPlan plan = publishedNodes.retrievalNode().plan();
        if (plan != null && !plan.nextRefreshTime().equals(publishedRefreshTime)) {
            publishedRefreshTime = plan.nextRefreshTime();
            nextRefreshTime.accept(plan.nextRefreshTime());
        }
    }

    @Override
    protected final void handlePanic(@Nullable String message, @Nullable Throwable cause) {
        publishedNodes = null;
        taskFailureReporter.onTaskException(GRAPH_TASK_NAME_PREFIX + tariffCode, new IllegalStateException(message, cause));
    }

    protected final String tariffCode() {
        return tariffCode;
    }

    private record PublishedNodes(PriceRetrievalNode retrievalNode, PricePublicationNode publicationNode) {
    }
}
