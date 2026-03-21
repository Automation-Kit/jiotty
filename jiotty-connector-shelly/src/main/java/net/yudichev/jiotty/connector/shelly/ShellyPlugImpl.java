package net.yudichev.jiotty.connector.shelly;

import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.Locks.inLock;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.connector.shelly.ShellyPlugImpl.SampleAggregator.MAX_SAMPLE_COUNT;

/// <a href="https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Switch">Guide</a>
class ShellyPlugImpl extends BaseLifecycleComponent implements ShellyPlug {
    private static final Logger logger = LogManager.getLogger(ShellyPlugImpl.class);

    private final String host;
    private final ExecutorFactory executorFactory;
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final Request requestPowerOn;
    private final Request requestPowerOff;
    private final Request requestGetStatus;
    private final CurrentDateTimeProvider timeProvider;
    private OkHttpClient httpClient;

    private SchedulingExecutor executor;

    @Nullable
    private ConsumptionMeasurementImpl activeMeasurement;

    @Inject
    ShellyPlugImpl(@Host String host,
                   ExecutorFactory executorFactory,
                   @Dependency RetryableOperationExecutor retryableOperationExecutor,
                   CurrentDateTimeProvider timeProvider) {
        this.host = checkNotNull(host);
        this.executorFactory = checkNotNull(executorFactory);
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
        this.timeProvider = checkNotNull(timeProvider);
        String baseUrl = "http://" + host;
        requestPowerOn = new Request.Builder().url(baseUrl + "/rpc/Switch.Set?id=0&on=true").get().build();
        requestPowerOff = new Request.Builder().url(baseUrl + "/rpc/Switch.Set?id=0&on=false").get().build();
        requestGetStatus = new Request.Builder().url(baseUrl + "/rpc/Switch.GetStatus?id=0").get().build();
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("ShellyPlug-" + host);
        httpClient = createHttpClient();
    }

    /// for tests
    OkHttpClient createHttpClient() {
        return newClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(httpClient), executor);
    }

    @Override
    public CompletableFuture<Boolean> powerOn() {
        return switchSet(requestPowerOn, "On");
    }

    @Override
    public CompletableFuture<Boolean> powerOff() {
        return switchSet(requestPowerOff, "Off");
    }

    @Override
    public CompletableFuture<SwitchStatus> getStatus() {
        return getSwitchStatus();
    }

    private CompletableFuture<Boolean> switchSet(Request request, String targetStateName) {
        return whenStartedAndNotLifecycling(() -> retryableOperationExecutor
                .withBackOffAndRetry(
                        "Shelly-Switch.Set" + targetStateName + "-" + host,
                        () -> call(httpClient.newCall(request), SwitchSetResponse.class, 0)))
                .thenApply(SwitchSetResponse::wasOn);
    }

    @Override
    public ConsumptionMeasurement startMeasuringConsumption(Consumer<String> errorHandler) {
        return whenStartedAndNotLifecycling(() -> {
            checkState(activeMeasurement == null, "Already started measuring consumption");
            return activeMeasurement = new ConsumptionMeasurementImpl(errorHandler);
        });
    }

    private CompletableFuture<SwitchStatus> getSwitchStatus() {
        return retryableOperationExecutor.withBackOffAndRetry("Shelly-Switch.GetStatus-" + host,
                                                              () -> call(httpClient.newCall(requestGetStatus), SwitchStatus.class, 0));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Host {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    private class ConsumptionMeasurementImpl implements ConsumptionMeasurement {

        private static final Duration SAMPLING_PERIOD = Duration.ofMinutes(1);

        private final Consumer<String> errorHandler;

        private final Lock lock = new ReentrantLock();
        /// `null` means we are stopped
        @Nullable
        private SampleAggregator sampleAggregator = new SampleAggregator();
        private Instant sampleStartTime;
        @Nullable
        private Closeable nextSamplingSchedule;

        public ConsumptionMeasurementImpl(Consumer<String> errorHandler) {
            this.errorHandler = checkNotNull(errorHandler);
            logger.info("[{}] Starting sampling consumption", host);
            executor.execute(this::sample);
        }

        @Override
        public Optional<ConsumptionCurve> snap() {
            return inLock(lock, () -> {
                checkState(isRunning(), "Consumption sampling already stopped or failed earlier");
                return sampleAggregator.generateConsumptionCurve();
            });
        }

        @Override
        public Optional<ConsumptionCurve> stop() {
            return inLock(lock, () -> {
                var result = snap();
                logger.info("[{}] Stopping consumption sampling", host);
                stopAndReleaseResources();
                return result;
            });
        }

        private void sample() {
            sampleStartTime = timeProvider.currentInstant();
            getSwitchStatus()
                    .whenCompleteAsync((switchStatus, e) -> {
                        if (inLock(lock, this::isRunning)) {
                            processResponse(switchStatus, e);
                        } else {
                            logger.debug("[{}] Discarded response after stop: {}", host, switchStatus, e);
                        }
                    }, executor);
        }

        @SuppressWarnings("TypeMayBeWeakened")
        private void processResponse(SwitchStatus switchStatus, Throwable e) {
            logger.debug("[{}] Processing response {}", host, switchStatus);
            if (e != null) {
                inLock(lock, this::stopAndReleaseResources);
                onFailure(humanReadableMessage(e));
            } else {
                try {
                    boolean canProceed = processResponse(switchStatus.energyStatus());
                    if (canProceed) {
                        var nextSampleStartTime = sampleStartTime.plus(SAMPLING_PERIOD);
                        var delayUntilNextSample = Duration.between(timeProvider.currentInstant(), nextSampleStartTime);
                        if (delayUntilNextSample.isNegative()) {
                            delayUntilNextSample = Duration.ZERO;
                        }
                        logger.debug("[{}] Processed, next sample in {}", host, delayUntilNextSample);
                        nextSamplingSchedule = executor.schedule(delayUntilNextSample, this::sample);
                    }
                } catch (RuntimeException ex) {
                    logger.error("[{}] Consumption response processing failed", host, ex);
                }
            }
        }

        /// @return `true` if can processed, otherwise indicates we are stopped
        private boolean processResponse(SwitchEnergyStatus switchEnergyStatus) {
            enum Outcome {EXCEEDED_MAX_SIZE, OK, ALREADY_STOPPED}
            Outcome outcome = inLock(lock, () -> {
                if (isRunning()) {
                    var weExceededMaxSize = sampleAggregator.processResponse(switchEnergyStatus);
                    if (weExceededMaxSize) {
                        stopAndReleaseResources();
                        return Outcome.EXCEEDED_MAX_SIZE;
                    }
                    return Outcome.OK;
                } else {
                    logger.info("[{}] Ignored response after stop: {}", host, switchEnergyStatus);
                    return Outcome.ALREADY_STOPPED;
                }
            });
            return switch (outcome) {
                case OK -> true;
                case EXCEEDED_MAX_SIZE -> {
                    onFailure("Sample count exceeded max size " + MAX_SAMPLE_COUNT);
                    yield false;
                }
                case ALREADY_STOPPED -> false;
            };
        }

        private void onFailure(String errorMessage) {
            try {
                errorHandler.accept(errorMessage);
                stopAndReleaseResources();
            } catch (RuntimeException ex) {
                logger.error("[{}] Error handler failed", host, ex);
            }
        }

        private boolean isRunning() {
            return sampleAggregator != null;
        }

        private void stopAndReleaseResources() {
            closeSafelyIfNotNull(logger, nextSamplingSchedule);
            nextSamplingSchedule = null;
            sampleAggregator = null;
            // allow starting new measurements
            whenStartedAndNotLifecycling(() -> {
                activeMeasurement = null;
            });
        }
    }

    class SampleAggregator {
        public static final int MAX_SAMPLE_COUNT = 10_000;

        private final TreeMap<Long, Double> consumptionByEpochSec = new TreeMap<>();

        @SuppressWarnings("TypeMayBeWeakened")
        public boolean processResponse(SwitchEnergyStatus switchEnergyStatus) {
            var startOfConsumptionMinuteSpochTimeSec = switchEnergyStatus.endOfNewestMinuteEpochTimeSec() - 60;
            for (Double consumption : switchEnergyStatus.mWHoursByMinute()) {
                var previousValue = consumptionByEpochSec.putIfAbsent(startOfConsumptionMinuteSpochTimeSec, consumption);
                if (logger.isDebugEnabled() && previousValue == null) {
                    logger.debug("[{}] Added sample {}->{}", host, Instant.ofEpochSecond(startOfConsumptionMinuteSpochTimeSec), consumption);
                }
                startOfConsumptionMinuteSpochTimeSec -= 60;
            }
            return consumptionByEpochSec.size() > MAX_SAMPLE_COUNT;
        }

        public Optional<ConsumptionCurve> generateConsumptionCurve() {
            if (consumptionByEpochSec.isEmpty()) {
                return Optional.empty();
            }
            var sampleListBuilder = ImmutableList.<Double>builderWithExpectedSize(consumptionByEpochSec.size() * 12 / 10);
            long curTime = consumptionByEpochSec.firstKey();
            long endTime = consumptionByEpochSec.lastKey();
            while (curTime <= endTime) {
                // find the entry nearest to the current time
                Map.Entry<Long, Double> floorEntry = consumptionByEpochSec.floorEntry(curTime);
                Map.Entry<Long, Double> ceilingEntry = consumptionByEpochSec.ceilingEntry(curTime);
                long distanceToFloor = curTime - floorEntry.getKey();
                long distanceToCeiling = ceilingEntry.getKey() - curTime;
                assert distanceToFloor >= 0 && distanceToCeiling >= 0;
                sampleListBuilder.add(distanceToFloor < distanceToCeiling ? floorEntry.getValue() : ceilingEntry.getValue());
                // next minute
                curTime += 60;
            }
            return Optional.of(new ConsumptionCurve(Instant.ofEpochSecond(consumptionByEpochSec.firstKey()), sampleListBuilder.build()));
        }
    }
}
