package net.yudichev.jiotty.connector.world.weather;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthReporting;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.misc.UpstreamHealthReporting.reportingHealth;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.common.security.LogRedaction.redacted;
import static okhttp3.HttpUrl.parse;

class WeatherServiceImpl extends BaseLifecycleComponent implements WeatherService {
    private static final Logger logger = LogManager.getLogger(WeatherServiceImpl.class);

    private static final String API_BASE = "https://api.weatherapi.com/v1";

    private final String apiKey;
    private final UpstreamHealthHandler healthHandler;
    /// Retries the shared-outage failures of every call, so only a sustained outage reaches [#healthHandler].
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final CurrentDateTimeProvider timeProvider;
    private final AtomicLong requestIdGenerator = new AtomicLong();
    private OkHttpClient client;

    @Inject
    WeatherServiceImpl(CurrentDateTimeProvider timeProvider,
                       @ApiKey String apiKey,
                       @Dependency UpstreamHealthHandler healthHandler,
                       @Dependency RetryableOperationExecutor retryableOperationExecutor) {
        this.timeProvider = checkNotNull(timeProvider);
        this.apiKey = checkNotNull(apiKey);
        this.healthHandler = checkNotNull(healthHandler);
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
    }

    @Override
    protected void doStart() {
        client = createHttpClient();
    }

    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<Weather> getCurrentWeather(LatLon worldCoordinates) {
        var request = buildGet("/current.json", worldCoordinates, null);
        long reqId = requestIdGenerator.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getCurrentWeather for {}", reqId, redacted(worldCoordinates));
        }
        return callApi("get current weather", request, WeatherResponse.class)
                .thenApply(WeatherResponse::current)
                .whenComplete((result, throwable) -> logger.debug("[{}] Response: {}", reqId, result, throwable));
    }

    @Override
    public CompletableFuture<List<ForecastHour>> getForecastWeather(LatLon worldCoordinates, Instant until) {
        Instant now = timeProvider.currentInstant();
        long secondsAhead = Duration.between(now, until).getSeconds();
        checkArgument(secondsAhead >= 0, "instant must be in the future, but was: %s", until);
        int daysToInclude = (int) Math.ceil(secondsAhead / 86400.0) + 1; // the API response includes today, so today + required future days
        if (daysToInclude < 1) {
            daysToInclude = 1;
        }
        checkArgument(daysToInclude <= MAX_FORECAST_DAYS + 1,
                      "Requested instant %s is too far in the future (%s days). Max days supported %s", until, daysToInclude, MAX_FORECAST_DAYS);

        int finalDaysToInclude = daysToInclude;
        var request = buildGet("/forecast.json", worldCoordinates, b -> b.addQueryParameter("days", String.valueOf(finalDaysToInclude)));
        long reqId = requestIdGenerator.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] getForecastWeather for {} until {} ({} days)", reqId, redacted(worldCoordinates), until, finalDaysToInclude);
        }
        return callApi("get weather forecast", request, ForecastResponse.class)
                .<List<ForecastHour>>thenApply(resp -> {
                    ImmutableList<ForecastDay> days = resp.forecast().days();
                    var resultBuilder = ImmutableList.<ForecastHour>builderWithExpectedSize(days.size() * 24);
                    Instant instant = null;
                    for (ForecastDay day : days) {
                        for (ForecastHour hour : day.hours()) {
                            if (instant != null) {
                                checkState(instant.equals(hour.from()), "current.from=%s != previous.to=%s in %s", hour.from(), instant, hour);
                            }
                            resultBuilder.add(hour);
                            instant = hour.to();
                        }
                    }
                    return resultBuilder.build();
                })
                .whenComplete((result, ex) -> logger.debug("[{}] Response: {}", reqId, result, ex));
    }

    private Request buildGet(String path, LatLon coords, Consumer<HttpUrl.Builder> extraParams) {
        var urlBuilder = checkNotNull(parse(API_BASE + path)).newBuilder()
                                                             .addQueryParameter("key", apiKey)
                                                             .addQueryParameter("q", coords.lat() + "," + coords.lon());
        if (extraParams != null) {
            extraParams.accept(urlBuilder);
        }
        var url = urlBuilder.build();
        return new Request.Builder().url(url).get().build();
    }

    /// Runs every call to the weather API through [UpstreamHealthReporting#reportingHealth], so one sustained outage is reported once, however many callers
    /// the shared service serves.
    private <T> CompletableFuture<T> callApi(String operationName, Request request, Class<? extends T> responseType) {
        return whenStartedAndNotLifecycling(
                () -> reportingHealth(retryableOperationExecutor, healthHandler, operationName, "weather API call failed",
                                      () -> call(client.newCall(request), responseType)));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiKey {
    }

    /// Qualifies the dependencies this service consumes, so the bindings never collide with unannotated ones of the same type in the
    /// parent injector or in a sibling connector.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
