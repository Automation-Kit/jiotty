package net.yudichev.jiotty.energy.octopus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.ForecastPrice;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSource;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.energy.octopus.Bindings.Dependency;
import static net.yudichev.jiotty.energy.octopus.Bindings.PriceForecast;

/// One instance per region letter, constructed and started lazily by [PriceForecastServiceRegistry]. The region is passed at construction and stays fixed for
/// the instance's lifetime.
///
/// Each refresh sweeps the [PriceForecastSource]s in order and publishes the first forecast that passes validation, so one source's outage costs one failed
/// fetch. A sweep with no serving source is retried with backoff by the injected retry executor; exhaustion of that retry window means every source failed
/// continuously throughout it, which is what [UpstreamHealthHandler#onFailure] reports. The last published forecast is also persisted, so a freshly started
/// instance serves it until the first sweep succeeds.
///
/// All mutable state is confined to the single-threaded PriceForecast executor.
public final class ForecastEnergyPriceServiceImpl extends BaseLifecycleComponent implements ForecastEnergyPriceService {
    @VisibleForTesting
    static final Duration RETRIEVAL_PERIOD = Duration.ofHours(1);
    @VisibleForTesting
    static final Duration MAX_PERSISTED_FORECAST_AGE = Duration.ofHours(24);
    private static final Logger logger = LogManager.getLogger(ForecastEnergyPriceServiceImpl.class);
    private static final String ATTEMPTS_COUNTER_NAME = "price_forecast_attempts_total";
    private static final String OUTCOME_FETCH_ERROR = "fetch_error";
    private static final String OUTCOME_INVALID_PAYLOAD = "invalid_payload";
    // TODO:commerce # of days must be a dynamic parameter of this service (will need to change on the fly)
    private static final int FORECAST_DAY_COUNT = 13;

    private final Provider<SchedulingExecutor> executorProvider;
    private final List<PriceForecastSource> sources;
    private final RetryableOperationExecutor retryExecutor;
    private final UpstreamHealthHandler statusHandler;
    private final CurrentDateTimeProvider timeProvider;
    private final @Nullable VarStore varStore;
    /// Parallel to [#sources]: the attempt counters of the source at the same index.
    private final List<SourceMeters> metersBySourceIndex;
    private final String region;
    private final String storeKey;
    /// The latest published forecast, `null` until the first one is produced; new subscribers receive the present value immediately. Confined to the executor,
    /// as is every subscription to it.
    private final ObservableValue<@Nullable Prices> latestForecast = ObservableValue.simple(null);

    private SchedulingExecutor executor;
    private Closeable refreshSchedule;
    /// Sweep of the current refresh; confined to the executor.
    private @Nullable CompletableFuture<SweepSuccess> inFlightSweep;
    /// Identifies the current sweep; a source completion carrying an older generation belongs to a superseded sweep. Confined to the executor.
    private int sweepGeneration;

    @Inject
    public ForecastEnergyPriceServiceImpl(@PriceForecast Provider<SchedulingExecutor> executorProvider,
                                          List<PriceForecastSource> sources,
                                          @Dependency RetryableOperationExecutor retryExecutor,
                                          @Dependency UpstreamHealthHandler statusHandler,
                                          @Dependency MeterRegistry meterRegistry,
                                          @Dependency Optional<VarStore> varStore,
                                          CurrentDateTimeProvider timeProvider,
                                          @Assisted char regionLetter) {
        this.executorProvider = checkNotNull(executorProvider);
        this.sources = ImmutableList.copyOf(sources);
        this.retryExecutor = checkNotNull(retryExecutor);
        this.statusHandler = checkNotNull(statusHandler);
        this.timeProvider = checkNotNull(timeProvider);
        this.varStore = varStore.orElse(null);
        var metersBuilder = ImmutableList.<SourceMeters>builderWithExpectedSize(sources.size());
        for (PriceForecastSource source : sources) {
            metersBuilder.add(new SourceMeters(meterRegistry.counter(ATTEMPTS_COUNTER_NAME, "source", source.name(), "outcome", "success"),
                                               meterRegistry.counter(ATTEMPTS_COUNTER_NAME, "source", source.name(), "outcome", OUTCOME_FETCH_ERROR),
                                               meterRegistry.counter(ATTEMPTS_COUNTER_NAME, "source", source.name(), "outcome", OUTCOME_INVALID_PAYLOAD)));
        }
        metersBySourceIndex = metersBuilder.build();
        region = String.valueOf(regionLetter);
        storeKey = "priceForecast.lastGood." + region;
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        // Enqueued ahead of the first refresh on the single-threaded executor, so the persisted forecast is published before any freshly fetched one.
        executor.execute("publishStoredForecast", this::publishStoredForecast);
        refreshSchedule = executor.scheduleAtFixedRate(Duration.ZERO, RETRIEVAL_PERIOD, this::refresh);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, refreshSchedule, () -> executor.execute("cancelSweep", () -> {
            sweepGeneration++;
            if (inFlightSweep != null) {
                inFlightSweep.cancel(true);
            }
        }));
    }

    @Override
    public Closeable subscribeToPrices(Consumer<Prices> consumer) {
        checkNotNull(consumer);
        return whenStartedAndNotLifecycling(() -> latestForecast.subscribe(executor, prices -> {
            if (prices != null) {
                consumer.accept(prices);
            }
        }));
    }

    private void refresh() {
        sweepGeneration++;
        if (inFlightSweep != null && !inFlightSweep.isDone()) {
            logger.info("[{}] Cancelling the forecast sweep still running from the previous refresh", region);
            inFlightSweep.cancel(true);
        }
        int generation = sweepGeneration;
        CompletableFuture<SweepSuccess> sweep = retryExecutor.withBackOffAndRetry("priceForecast.sweep." + region, () -> sweepSources(generation));
        inFlightSweep = sweep;
        // The retry executor completes the sweep on its own thread and keeps doing so past this component's stop, so the hop back is guarded on both ends:
        // tryExecute drops the task once the executor is closed, ifNotStopped drops it once teardown has begun.
        sweep.whenComplete((sweepSuccess, throwable) -> executor.tryExecute("handleSweepResult", () -> ifNotStopped(() -> {
            if (throwable == null) {
                handleSweepSuccess(sweepSuccess);
            } else if (!(throwable instanceof CancellationException)) {
                statusHandler.onFailure("All price forecast sources failed for region " + region, throwable);
            }
        })));
    }

    private CompletableFuture<SweepSuccess> sweepSources(int generation) {
        logger.info("[{}] Requesting price forecast for {} days", region, FORECAST_DAY_COUNT);
        return attemptSource(generation, 0, new ArrayList<>(sources.size()), null);
    }

    /// @param lastFailure the previous source's failure, `null` on the first source's attempt
    private CompletableFuture<SweepSuccess> attemptSource(int generation, int sourceIndex, List<String> failureSummaries, @Nullable Throwable lastFailure) {
        if (sourceIndex == sources.size()) {
            return failedFuture(new RuntimeException("All price forecast sources failed: " + String.join(", ", failureSummaries), lastFailure));
        }
        PriceForecastSource source = sources.get(sourceIndex);
        return source.getPrices(region, FORECAST_DAY_COUNT)
                     .handleAsync((forecastPrices, throwable) -> handleSourceResult(generation,
                                                                                    source,
                                                                                    sourceIndex,
                                                                                    forecastPrices,
                                                                                    throwable,
                                                                                    failureSummaries),
                                  executor)
                     .thenCompose(Function.identity());
    }

    /// @param forecastPrices the fetched payload, `null` when the fetch failed
    /// @param fetchFailure   the fetch failure, `null` when the fetch succeeded
    private CompletableFuture<SweepSuccess> handleSourceResult(int generation,
                                                               PriceForecastSource source,
                                                               int sourceIndex,
                                                               @Nullable List<ForecastPrice> forecastPrices,
                                                               @Nullable Throwable fetchFailure,
                                                               List<String> failureSummaries) {
        if (generation != sweepGeneration) {
            // cancelling the sweep's future leaves an already-issued fetch running; its completion lands here, where the superseded sweep ends instead of
            // metering the outcome and fetching from the remaining sources
            return failedFuture(new CancellationException());
        }
        SourceMeters meters = metersBySourceIndex.get(sourceIndex);
        Throwable failure;
        String outcome;
        if (fetchFailure == null) {
            assert forecastPrices != null; // a normally completed fetch carries its payload
            try {
                Prices prices = ForecastPriceProfiles.toPrices(forecastPrices, timeProvider.currentInstant(), region + '/' + source.name());
                meters.success().increment();
                return completedFuture(new SweepSuccess(source.name(), prices));
            } catch (RuntimeException e) {
                failure = e;
                outcome = OUTCOME_INVALID_PAYLOAD;
                meters.invalidPayload().increment();
            }
        } else {
            failure = fetchFailure;
            outcome = OUTCOME_FETCH_ERROR;
            meters.fetchError().increment();
        }
        logger.info("[{}] Forecast source {} failed ({}): {}", region, source.name(), outcome, humanReadableMessageFormattable(failure));
        failureSummaries.add(source.name() + '=' + outcome);
        return attemptSource(generation, sourceIndex + 1, failureSummaries, failure);
    }

    private void handleSweepSuccess(SweepSuccess sweepSuccess) {
        String sourceName = sweepSuccess.sourceName();
        Prices prices = sweepSuccess.prices();
        if (!sourceName.equals(sources.getFirst().name())) {
            logger.info("[{}] Forecast served by fallback source {}", region, sourceName);
        }
        latestForecast.accept(prices);
        statusHandler.onSuccess();
        if (varStore != null) {
            varStore.saveValue(storeKey, StoredForecast.builder()
                                                       .setSavedAt(timeProvider.currentInstant())
                                                       .setSource(sourceName)
                                                       .setProfileStart(prices.profileStart())
                                                       .setIntervalLengthSec(prices.profile().intervalLengthSec())
                                                       .setPrices(prices.profile().pricePerInterval())
                                                       .build());
        }
    }

    private void publishStoredForecast() {
        if (varStore == null) {
            return;
        }
        varStore.readValue(StoredForecast.class, storeKey).ifPresent(storedForecast -> {
            Instant now = timeProvider.currentInstant();
            Duration age = Duration.between(storedForecast.savedAt(), now);
            Instant profileEnd = storedForecast.profileStart().plusSeconds((long) storedForecast.intervalLengthSec() * storedForecast.prices().size());
            if (age.compareTo(MAX_PERSISTED_FORECAST_AGE) <= 0 && profileEnd.isAfter(now)) {
                logger.info("[{}] Serving forecast persisted {} ago from source {}, covering until {}", region, age, storedForecast.source(), profileEnd);
                latestForecast.accept(new Prices(storedForecast.profileStart(),
                                                 new PriceProfile(storedForecast.intervalLengthSec(), 0, storedForecast.prices())));
            } else {
                logger.info("[{}] Ignoring persisted forecast: saved {} ago, covers until {}", region, age, profileEnd);
            }
        });
    }

    /// Produces the per-region instances the registry owns. It yields the implementation type because the registry, unlike a forecast subscriber, drives each
    /// instance's lifecycle.
    public interface Factory {
        ForecastEnergyPriceServiceImpl create(char regionLetter);
    }

    private record SourceMeters(Counter success, Counter fetchError, Counter invalidPayload) {}

    /// A completed sweep: the [PriceForecastSource#name()] of the serving source and its validated forecast.
    private record SweepSuccess(String sourceName, Prices prices) {}
}
