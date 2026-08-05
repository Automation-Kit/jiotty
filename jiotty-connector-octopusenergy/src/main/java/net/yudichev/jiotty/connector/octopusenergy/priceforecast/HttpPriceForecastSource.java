package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.rest.RestClients;
import okhttp3.Call;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;

/// A [PriceForecastSource] over one HTTP API: formats the URL from a template, parses the response into the API's envelope type and extracts the
/// [ForecastPrice]s from it.
///
/// Each fetch is a single request attempt, so a caller failing over between sources moves on after one failure.
///
/// @param <R> the API's response envelope type
final class HttpPriceForecastSource<R> implements PriceForecastSource {
    private static final Logger logger = LogManager.getLogger(HttpPriceForecastSource.class);

    private final AtomicInteger requestIdGenerator = new AtomicInteger();
    private final String name;
    private final Call.Factory callFactory;
    /// [String#format] template with the region letter as the first argument and the day count as the second; a template for an API without a day-count
    /// parameter refers to the first argument alone.
    private final String urlTemplate;
    private final TypeToken<R> responseType;
    private final Function<? super R, ? extends List<ForecastPrice>> priceExtractor;

    public HttpPriceForecastSource(String name,
                                   Call.Factory callFactory,
                                   String urlTemplate,
                                   TypeToken<R> responseType,
                                   Function<? super R, ? extends List<ForecastPrice>> priceExtractor) {
        this.name = checkNotNull(name);
        checkArgument(!name.isBlank(), "name must not be blank");
        this.callFactory = checkNotNull(callFactory);
        this.urlTemplate = checkNotNull(urlTemplate);
        this.responseType = checkNotNull(responseType);
        this.priceExtractor = checkNotNull(priceExtractor);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CompletableFuture<List<ForecastPrice>> getPrices(String region, int dayCount) {
        verify(region.length() == 1 && Character.isLetter(region.charAt(0)), "Invalid region: '%s', must be a single letter", region);
        verify(dayCount >= 1 && dayCount <= 365, "Must have 365 >= dayCount >= 1 but was: %s", dayCount);
        var url = String.format(urlTemplate, region, dayCount);
        var requestId = requestIdGenerator.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] Calling {}", requestId, url);
        }
        // One request attempt per fetch: the caller's failover across sources plus its own retry policy own all repeat attempts.
        return RestClients.call(callFactory.newCall(new Request.Builder().url(url).get().build()), responseType, 0)
                          .thenApply(response -> {
                              if (logger.isDebugEnabled()) {
                                  logger.debug("[{}] Result: {}", requestId, response);
                              }
                              return priceExtractor.apply(response);
                          });
    }

    @Override
    public String toString() {
        return name;
    }
}
