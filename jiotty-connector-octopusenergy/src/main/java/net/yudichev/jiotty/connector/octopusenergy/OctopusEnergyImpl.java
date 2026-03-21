package net.yudichev.jiotty.connector.octopusenergy;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.BaseEncoding.base64;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

/// <a href="https://octopus.energy/blog/agile-smart-home-diy/">Guide 1</a>,
/// <a href="https://www.guylipman.com/octopus/api_guide.html">Guide 2</a>
public final class OctopusEnergyImpl extends BaseLifecycleComponent implements OctopusEnergy {
    private static final Logger logger = LogManager.getLogger(OctopusEnergyImpl.class);

    private static final String BASE_URL = "https://api.octopus.energy/v1";

    private final String apiKey;
    private final String accountId;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private OkHttpClient client;
    private CompletableFuture<OctopusAccount> account;

    @Inject
    public OctopusEnergyImpl(CurrentDateTimeProvider currentDateTimeProvider, @ApiKey String apiKey, @AccountId String accountId) {
        this.apiKey = checkNotNull(apiKey);
        this.accountId = checkNotNull(accountId);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
    }

    @Override
    protected void doStart() {
        client = newClient();
        account = call(client.newCall(new Request.Builder()
                                              .url(BASE_URL + "/accounts/" + accountId)
                                              .header("Authorization", "Basic " + base64().encode(apiKey.getBytes(StandardCharsets.US_ASCII)))
                                              .get()
                                              .build()),
                       new TypeToken<>() {});
        account.whenComplete(CompletableFutures.logErrorOnFailure(logger, "Failed to retrieve account info"));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<List<StandardUnitRate>> getAgilePrices(Instant periodFrom, Instant periodTo) {
        return account
                .thenApply(this::extractCurrentTariff)
                .thenCompose(tariff -> {
                    var tariffCode = tariff.tariffCode();
                    var productCode = tariffCode.substring(5, tariffCode.length() - 2);
                    return getStandardUnitRates(BASE_URL + "/products/" + productCode + "/electricity-tariffs/" + tariffCode + "/standard-unit-rates/" +
                                                "?period_from=" + periodFrom +
                                                "&period_to=" + periodTo);
                }).thenApply(StandardUnitRates::rates);
    }

    private CompletableFuture<StandardUnitRates> getStandardUnitRates(String url) {
        logger.debug("Calling {}", url);
        return call(client.newCall(new Request.Builder().url(url).get().build()), new TypeToken<StandardUnitRates>() {})
                .thenCompose(standardUnitRates -> standardUnitRates
                        // if next page, recursively call it and concatenate
                        .nextUrl()
                        .map(nextUrl -> getStandardUnitRates(nextUrl)
                                .thenApply(nextRates -> StandardUnitRates.of(
                                        ImmutableList.<StandardUnitRate>builderWithExpectedSize(standardUnitRates.rates().size() + nextRates.rates().size())
                                                     .addAll(standardUnitRates.rates())
                                                     .addAll(nextRates.rates())
                                                     .build())))
                        .orElseGet(() -> CompletableFuture.completedFuture(standardUnitRates)));
    }


    private Tariff extractCurrentTariff(@SuppressWarnings("TypeMayBeWeakened") OctopusAccount account) {
        return account.properties().stream().findFirst()
                      .flatMap(accountProperty -> accountProperty.electricityMeterPoints().stream().findFirst())
                      .flatMap(electricityMeterPoint -> electricityMeterPoint.tariffs().stream().filter(this::isCurrent).findFirst())
                      .orElseThrow(() -> new RuntimeException("Unable to extract current tariff from " + account));

    }

    private boolean isCurrent(@SuppressWarnings("TypeMayBeWeakened") Tariff tariff) {
        var now = currentDateTimeProvider.currentInstant();
        return tariff.validFrom().equals(now) || tariff.validFrom().isBefore(now) && tariff.validTo().isAfter(now);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiKey {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccountId {
    }
}
