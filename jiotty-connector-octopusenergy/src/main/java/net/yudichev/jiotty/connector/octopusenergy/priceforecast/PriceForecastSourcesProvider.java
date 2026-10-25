package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

/// Creates the [PriceForecastSource]s, in failover order, sharing one HTTP client whose lifetime this component owns.
public final class PriceForecastSourcesProvider extends BaseLifecycleComponent implements Provider<List<PriceForecastSource>> {
    private static final Logger logger = LogManager.getLogger(PriceForecastSourcesProvider.class);
    /// The characters OkHttp accepts in a header value, so a bad value fails here rather than on every fetch.
    private static final CharMatcher HEADER_VALUE_CHARS = CharMatcher.inRange(' ', '~');

    private final @Nullable String userAgent;
    private OkHttpClient client;
    private List<PriceForecastSource> sources;

    @Inject
    public PriceForecastSourcesProvider(@UserAgent Optional<String> userAgent) {
        this.userAgent = checkNotNull(userAgent).orElse(null);
        checkArgument(this.userAgent == null || (!this.userAgent.isBlank() && HEADER_VALUE_CHARS.matchesAllOf(this.userAgent)),
                      "userAgent must be non-blank printable ASCII");
    }

    @Override
    protected void doStart() {
        // 20s cap on each whole call: a hung source must not stall a failover sweep across all sources.
        client = newClient(builder -> builder.callTimeout(Duration.ofSeconds(20)));
        sources = createSources(client, userAgent);
    }

    @VisibleForTesting
    static List<PriceForecastSource> createSources(Call.Factory callFactory, @Nullable String userAgent) {
        Call.Factory sourceCallFactory = userAgent == null
                                         ? callFactory
                                         : request -> callFactory.newCall(request.newBuilder().header(USER_AGENT, userAgent).build());
        var agilePredictEnvelopeType = new TypeToken<List<AgilePredictPricesResponse>>() {};
        // Failover order is by measured accuracy, best first: agileforecast.co.uk's matched-slot comparison (July 2026) put MAE at 3-7 days at
        // 3.6 p/kWh for agilepredict, 4.5 for x2r and 5.4 for agileforecast.
        return ImmutableList.of(new HttpPriceForecastSource<>("agilepredict",
                                                              sourceCallFactory,
                                                              "https://agilepredict.com/api/%s/?days=%d&high_low=false",
                                                              agilePredictEnvelopeType,
                                                              PriceForecastSourcesProvider::extractEnvelopePrices),
                                new HttpPriceForecastSource<>("x2r",
                                                              sourceCallFactory,
                                                              "https://api.x2r.uk/agile/%s",
                                                              new TypeToken<>() {},
                                                              PriceForecastSourcesProvider::extractX2rPrices),
                                new HttpPriceForecastSource<>("agileforecast",
                                                              sourceCallFactory,
                                                              "https://agileforecast.co.uk/api/%s/?days=%d&high_low=false",
                                                              agilePredictEnvelopeType,
                                                              PriceForecastSourcesProvider::extractEnvelopePrices));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public List<PriceForecastSource> get() {
        return whenStartedAndNotLifecycling(() -> sources);
    }

    private static List<ForecastPrice> extractEnvelopePrices(List<AgilePredictPricesResponse> responses) {
        checkArgument(!responses.isEmpty(), "response contains no forecasts");
        return responses.getFirst().prices();
    }

    private static List<ForecastPrice> extractX2rPrices(X2rResponse response) {
        List<X2rForecastPrice> forecast = response.prices().forecast();
        var prices = ImmutableList.<ForecastPrice>builderWithExpectedSize(forecast.size());
        for (X2rForecastPrice price : forecast) {
            prices.add(ForecastPrice.builder()
                                    .setDateTime(price.dateTime())
                                    .setPredictedPrice(price.predictedPrice())
                                    .build());
        }
        return prices.build();
    }

    /// The User-Agent every source's requests carry, naming the application to the services' operators; when empty, OkHttp's default goes out.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface UserAgent {
    }
}
