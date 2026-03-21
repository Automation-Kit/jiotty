package net.yudichev.jiotty.common.graph.server;

import jakarta.annotation.Nullable;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.backoff.ExponentialBackOff;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public abstract class BaseGraphBasedServer extends BaseLifecycleComponent {
    private static final int PANIC_COUNT_BEFORE_ALERT = 10;

    protected final CurrentDateTimeProvider timeProvider;
    private final Logger logger = LogManager.getLogger(getClass());
    private final Provider<SchedulingExecutor> executorProvider;
    private final List<ServerNode> nodes = new ArrayList<>();
    private final ExponentialBackOff reinitBackoff;

    protected int panicCount;
    @Nullable
    protected String panicReason;
    private SchedulingExecutor executor;
    private Closeable panicResetSchedule;
    @Nullable
    private GraphRunner graphRunner;

    protected BaseGraphBasedServer(Provider<SchedulingExecutor> executorProvider, CurrentDateTimeProvider timeProvider, DoubleSupplier backoffRng) {
        this.executorProvider = checkNotNull(executorProvider);
        this.timeProvider = checkNotNull(timeProvider);
        reinitBackoff = new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(5_000)
                .setMultiplier(1.5)
                .setMaxIntervalMillis(30_000)
                .setMaxElapsedTimeMillis(Integer.MAX_VALUE)
                .setRng(backoffRng)
                .build();
    }

    @Override
    protected final void doStart() {
        executor = executorProvider.get();
        doStart0();
        executor.execute(() -> {
            try {
                createGraph();
            } catch (RuntimeException e) {
                panic(e);
            }
        });
    }

    protected final SchedulingExecutor executor() {
        return executor;
    }

    protected void doStart0() {
    }

    @Override
    protected final void doStop() {
        doStop0();
        awaitShutdown(executor.submit(() -> closeSafelyIfNotNull(logger, nodes.reversed())).whenComplete(logErrorOnFailure(logger, "failed closing nodes")));
    }

    protected void doStop0() {
    }

    protected abstract void createNodes(GraphRunner graphRunner, NodeRegistrator registrator);

    protected abstract void recordState();

    protected final boolean graphActive() {
        return graphRunner != null;
    }

    /// For tests.
    protected void awaitShutdown(CompletableFuture<Void> nodeClosingFuture) {
        getAsUnchecked(() -> nodeClosingFuture.get(5, SECONDS));
    }

    protected void handlePanic(String reason) {
    }

    private void createGraph() {
        logger.info("Creating graph");
        var graph = new Graph(timeProvider, this::panic);
        graphRunner = new GraphRunner(graph, executor) {

            @Override
            public void scheduleNewWave(String triggeredBy) {
                if (graph().inWave()) {
                    logger.debug("Not scheduling new wave triggered by '{}' because already in wave", triggeredBy);
                    return;
                }
                if (isClosedPlain()) {
                    logger.debug("Not scheduling anything as closed");
                    return;
                }
                executor().execute(() -> {
                    if (isClosed()) {
                        logger.debug("Not starting new wave as closed");
                        return;
                    }
                    if (panicReason != null) {
                        logger.debug("Not starting new wave as in panic");
                        return;
                    }
                    logger.debug("New wave triggered at least by {}", triggeredBy);
                    graph().runWaves();
                    recordState();

                    if (panicReason != null) {
                        reinitBackoff.reset();
                    }
                });
            }

            @Override
            public void panic(String reason) {
                BaseGraphBasedServer.this.panic(reason);
            }
        };

        logger.debug("Creating nodes");
        createNodes(graphRunner, this::addNode);
        logger.debug("{} node(s) created, registering them in graph", nodes.size());
        nodes.forEach(ServerNode::registerInGraph);
        logger.debug("{} node(s) registered in graph", nodes.size());
        graphRunner.scheduleNewWave("Nodes registered");
    }

    private <T extends ServerNode> T addNode(T node) {
        nodes.add(node);
        return node;
    }

    private void logState(String when) {
        nodes.forEach(node -> node.logState(when));
    }

    private void panic(RuntimeException e) {
        logger.info("Panic", e);
        panic(humanReadableMessage(e));
    }

    private void panic(String reason) {
        if (panicReason != null) {
            logger.info("Additional panic ({}) while in panic state, ignoring new panic: {}", panicReason, reason);
        } else {
            try {
                logger.info("Panic: {}, resetting", reason);
                panicReason = checkNotNull(reason);
                handlePanic(reason);
                if (++panicCount == PANIC_COUNT_BEFORE_ALERT) {
                    closeIfNotNull(panicResetSchedule);
                    panicResetSchedule = executor.schedule(Duration.ofHours(1), () -> {
                        logger.debug("1 hour without panic - resetting panic count");
                        panicCount = 0;
                    });
                    logger.error("Panic count reached {}, last reason: {}", PANIC_COUNT_BEFORE_ALERT, reason);
                }
                logState("Panic");
                assert graphRunner != null;
                if (!graphRunner.graph().inWave()) {
                    // outside recalc wave, record state manually to capture panic reason
                    recordState();
                }
            } catch (RuntimeException e) {
                logger.error("Panic handling failed", e);
                if (panicReason == null) {
                    panicReason = "Panic handling failed: " + humanReadableMessage(e);
                }
            }
            reset();
        }
    }

    private void reset() {
        logger.debug("Closing graph");
        closeSafelyIfNotNull(logger, graphRunner);
        nodes.clear();
        graphRunner = null;

        var delay = Duration.ofMillis(reinitBackoff.nextBackOffMillis());
        logger.info("Will re-init after {}", delay);
        executor.schedule(delay, () -> {
            panicReason = null;
            createGraph();
        });
    }

    protected interface NodeRegistrator {
        <T extends ServerNode> T register(T node);
    }
}
