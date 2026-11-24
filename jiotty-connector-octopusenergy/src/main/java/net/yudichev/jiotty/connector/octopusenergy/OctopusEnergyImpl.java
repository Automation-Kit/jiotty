package net.yudichev.jiotty.connector.octopusenergy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ConcurrentDeduplicatingConsumer;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthReporting;
import net.yudichev.jiotty.common.observer.ObservableValue;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.security.AuthState;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.BaseEncoding.base64;
import static java.lang.Math.toIntExact;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.RestClients.DEFAULT_CALL_RETRY_COUNT;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.callSuppressingResponseBody;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.paginate;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

/// [Guide 1](https://octopus.energy/blog/agile-smart-home-diy/), [Guide 2](https://www.guylipman.com/octopus/api_guide.html)
@SuppressWarnings("WeakerAccess") // public outer surface; see java-style "internal APIs on non-public types" rule (applies even though this class is public)
public class OctopusEnergyImpl extends BaseLifecycleComponent implements OctopusEnergy {
    static final String BASE_URL = "https://api.octopus.energy/v1";
    /// The number of products that [RestClients#paginate] sizes its list for on a `/products/` fetch; a larger number costs only unused capacity.
    private static final int EXPECTED_PRODUCT_COUNT = 265;
    /// Octopus rates / standing charges arrive in half-hour slots, so this is the divisor that converts a `(from, to)` window into a slot count.
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    /// Sizing hint for a day or night rate fetch: those rates change with the price cap, a few times a year, so a request returns a handful of rows.
    private static final int DAY_OR_NIGHT_RATE_COUNT_HINT = 4;
    /// Page size requested on every ranged half-hourly fetch (consumption / rates). Octopus defaults to 100 rows per page, which turns a year of half-hourly
    /// data (~17 500 rows) into ~176 sequential page requests taking minutes — the long sequential sweep is what eventually catches a transient gateway 502 and
    /// fails the whole fetch. Requesting the maximum collapses a year of consumption into a single request (~1 s) and a year of rates into ~12 pages (the rates
    /// endpoint caps each page at 1 500 regardless), so the request count — and the 502 exposure window — shrinks by ~100×. Pagination still follows `next`, so
    /// multi-year ranges beyond one page are handled normally.
    @VisibleForTesting
    static final int MAX_PAGE_SIZE = 25_000;
    private static final TypeToken<OctopusAccountData> ACCOUNT_TYPE = new TypeToken<>() {};
    private static final TypeToken<ConsumptionPage> CONSUMPTION_PAGE_TYPE = new TypeToken<>() {};
    private static final Logger logger = LogManager.getLogger(OctopusEnergyImpl.class);

    private final Map<Character, RegionServiceImpl> regionServices = new ConcurrentHashMap<>();
    private final Map<AccountKey, AccountServiceImpl> accountServices = new ConcurrentHashMap<>();
    /// Notified of the Octopus API's health as each call completes, authenticated or open — see [#reportingHealth(String, Supplier)].
    private final UpstreamHealthHandler healthHandler;
    /// Retries the shared-outage failures of every call, so only a sustained outage reaches [#healthHandler].
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final TaskFailureReporter taskFailureReporter;

    private OkHttpClient client;

    @Inject
    public OctopusEnergyImpl(@Dependency UpstreamHealthHandler healthHandler,
                             @Dependency RetryableOperationExecutor retryableOperationExecutor,
                             TaskFailureReporter taskFailureReporter) {
        this.healthHandler = checkNotNull(healthHandler);
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
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
    public OctopusRegionService region(char regionLetter) {
        checkArgument(MpanRegionResolver.isValidRegion(regionLetter), "Unknown Octopus region letter: %s", regionLetter);
        return regionServices.computeIfAbsent(regionLetter, RegionServiceImpl::new);
    }

    @Override
    public OctopusAccountService account(String accountId, String apiKey) {
        checkArgument(accountId != null && !accountId.isBlank(), "accountId must be non-blank");
        checkArgument(apiKey != null && !apiKey.isBlank(), "apiKey must be non-blank");
        return accountServices.computeIfAbsent(new AccountKey(accountId, apiKey), AccountServiceImpl::new);
    }

    @Override
    public CompletableFuture<List<Product>> listProducts(Instant availableAt) {
        return reportingHealth("list Octopus products",
                               () -> paginate(this::openGetCall, BASE_URL + "/products/?available_at=" + availableAt,
                                              EXPECTED_PRODUCT_COUNT,
                                              new TypeToken<>() {}, ProductsPage::results, ProductsPage::nextUrl));
    }

    @Override
    public CompletableFuture<ProductDetails> getProductDetails(String code) {
        return reportingHealth("get Octopus product details", () -> call(openGetCall(BASE_URL + "/products/" + code + "/"), new TypeToken<>() {}));
    }

    /// [UpstreamHealthReporting#reportingHealth] with this connector's fixed failure message; every call path in this connector goes through it.
    private <T> CompletableFuture<T> reportingHealth(String operationName, Supplier<CompletableFuture<T>> operation) {
        return UpstreamHealthReporting.reportingHealth(retryableOperationExecutor, healthHandler, operationName, "Octopus API call failed", operation);
    }

    @VisibleForTesting
    static List<MpanAndMeter> extractMpanAndMeter(OctopusAccountData data) {
        return data.properties().stream()
                   .flatMap(property -> property.electricityMeterPoints().stream())
                   .flatMap(meterPoint -> meterPoint.meters().stream()
                                                    .map(meter -> MpanAndMeter.of(meterPoint.mpan(), meter.serialNumber())))
                   .toList();
    }

    /// Builds a GET [Call] for an open (no-auth) Octopus endpoint. Suitable as the call-factory parameter for [RestClients#paginate] and for ad-hoc single-shot
    /// fetches that don't need auth headers.
    private Call openGetCall(String url) {
        return loggedCall(new Request.Builder().url(url).get().build(), null);
    }

    /// Turns `request` into a [Call] on this connector's HTTP client after one DEBUG log line, so every code path that builds a [Call] logs the same shape.
    ///
    /// @param loggableUrl what the log line shows in place of the request URL, or `null` to show the URL itself
    private Call loggedCall(Request request, @Nullable StringFormattable loggableUrl) {
        logger.debug("Calling {}", loggableUrl == null ? request.url() : loggableUrl);
        return client.newCall(request);
    }

    /// Returns a loggable stand-in for `url`, with `***` in place of every occurrence of each of `personalValues`. The replacement runs only when the
    /// stand-in is written out.
    private static StringFormattable maskPersonalValues(String url, String... personalValues) {
        return appendable -> {
            String maskedUrl = url;
            for (String personalValue : personalValues) {
                maskedUrl = maskedUrl.replace(personalValue, "***");
            }
            Append.to(appendable, maskedUrl);
        };
    }

    @VisibleForTesting
    static String accountUrl(String accountId) {
        return BASE_URL + "/accounts/" + accountId;
    }

    private record AccountKey(String accountId, String apiKey) {
        @Override
        public String toString() {
            // mask PII
            return "AccountKey[accountId=***, apiKey=***]";
        }
    }

    private final class RegionServiceImpl extends BaseIdempotentCloseable implements OctopusRegionService {
        private final char regionLetter;

        RegionServiceImpl(char regionLetter) {
            this.regionLetter = regionLetter;
        }

        @Override
        public CompletableFuture<List<StandardUnitRate>> getStandardUnitRates(String productCode, String tariffCode, Instant from, Instant to) {
            // At most one row per half hour, Agile's slot length; a banded or flat tariff returns fewer. One slot of headroom is cheap.
            return getUnitRates("standard-unit-rates/", "get Octopus unit rates", productCode, tariffCode, from, to, expectedHalfHourCount(from, to));
        }

        @Override
        public CompletableFuture<List<StandardUnitRate>> getDayUnitRates(String productCode, String tariffCode, Instant from, Instant to) {
            return getUnitRates("day-unit-rates/", "get Octopus day unit rates", productCode, tariffCode, from, to, DAY_OR_NIGHT_RATE_COUNT_HINT);
        }

        @Override
        public CompletableFuture<List<StandardUnitRate>> getNightUnitRates(String productCode, String tariffCode, Instant from, Instant to) {
            return getUnitRates("night-unit-rates/", "get Octopus night unit rates", productCode, tariffCode, from, to, DAY_OR_NIGHT_RATE_COUNT_HINT);
        }

        private CompletableFuture<List<StandardUnitRate>> getUnitRates(String endpoint,
                                                                       String operationName,
                                                                       String productCode,
                                                                       String tariffCode,
                                                                       Instant from,
                                                                       Instant to,
                                                                       int expectedRateCount) {
            if (tariffRegionMismatch(tariffCode)) {
                return CompletableFuture.failedFuture(tariffRegionMismatchError(tariffCode));
            }
            String url = tariffsUrl(productCode, tariffCode) + endpoint + "?page_size=" + MAX_PAGE_SIZE
                         + "&period_from=" + from + "&period_to=" + to;
            return reportingHealth(operationName,
                                   () -> paginate(OctopusEnergyImpl.this::openGetCall,
                                                  url,
                                                  expectedRateCount,
                                                  new TypeToken<>() {},
                                                  StandardUnitRates::rates,
                                                  StandardUnitRates::nextUrl));
        }

        @Override
        public CompletableFuture<List<StandingCharge>> getStandingCharges(String productCode, String tariffCode, Instant from, Instant to) {
            if (tariffRegionMismatch(tariffCode)) {
                return CompletableFuture.failedFuture(tariffRegionMismatchError(tariffCode));
            }
            String url = tariffsUrl(productCode, tariffCode) + "standing-charges/?page_size=" + MAX_PAGE_SIZE
                         + "&period_from=" + from + "&period_to=" + to;

            // Standing charges change at most a handful of times per tariff per year; a small size hint is enough — overshoot is cheap.
            return reportingHealth("get Octopus standing charges",
                                   () -> paginate(OctopusEnergyImpl.this::openGetCall,
                                                  url,
                                                  4,
                                                  new TypeToken<>() {},
                                                  StandingChargesPage::results,
                                                  StandingChargesPage::nextUrl));
        }

        private static String tariffsUrl(String productCode, String tariffCode) {
            return BASE_URL + "/products/" + productCode + "/electricity-tariffs/" + tariffCode + "/";
        }

        /// The trailing letter of `tariffCode` MUST match this service's region — otherwise the caller is asking the wrong service, and the rates / standing
        /// charges returned would not correspond to the region they think they're scoped to.
        private boolean tariffRegionMismatch(String tariffCode) {
            return tariffCode.isEmpty() || tariffCode.charAt(tariffCode.length() - 1) != regionLetter;
        }

        private IllegalArgumentException tariffRegionMismatchError(String tariffCode) {
            return new IllegalArgumentException("tariffCode '" + tariffCode + "' is not for region " + regionLetter);
        }

        @Override
        protected void doClose() {
            regionServices.remove(regionLetter, this);
        }
    }

    private final class AccountServiceImpl extends BaseIdempotentCloseable implements OctopusAccountService {
        private final AccountKey key;
        private final ObservableValue<AuthState> authState = ObservableValue.concurrent(
                new AuthState.TransientFailure("Initialising"),
                e -> taskFailureReporter.onTaskException("Delivering the Octopus account authentication state", e));
        /// Forwards into [#authState] only on a *type-level* transition — [AuthState.Success] → [AuthState.Success] and [AuthState.PermanentFailure] →
        /// [AuthState.PermanentFailure] are deduped so subscribers aren't spammed by every successful authenticated call. The CAS inside
        /// [ConcurrentDeduplicatingConsumer] also makes the concurrent case (two [CompletableFuture#whenComplete] callbacks landing on different dispatcher
        /// threads) safe.
        private final Consumer<AuthState> authStateUpdater = new ConcurrentDeduplicatingConsumer<>(
                (previous, next) -> previous.getClass() == next.getClass(), authState);
        private final String accountUrl;
        private final StringFormattable loggableAccountUrl;

        AccountServiceImpl(AccountKey key) {
            this.key = key;
            accountUrl = accountUrl(key.accountId());
            loggableAccountUrl = maskPersonalValues(accountUrl, key.accountId());
        }

        @Override
        public Closeable subscribeToAuthState(Consumer<AuthState> handler) {
            return authState.subscribe(handler);
        }

        @Override
        public CompletableFuture<OctopusAccountData> getAccount() {
            // The account carries the customer's address and supply identifiers, so callSuppressingResponseBody omits the response body from every log line
            // and failure message.
            CompletableFuture<OctopusAccountData> future = reportingHealth("get Octopus account",
                                                                           () -> callSuppressingResponseBody(authedGetCall(accountUrl, loggableAccountUrl),
                                                                                                             ACCOUNT_TYPE,
                                                                                                             DEFAULT_CALL_RETRY_COUNT,
                                                                                                             loggableAccountUrl));
            future.whenComplete(this::updateAuthStateFromOutcome);
            return future;
        }

        @Override
        public CompletableFuture<List<MpanAndMeter>> getMpanAndMeter() {
            return getAccount().thenApply(OctopusEnergyImpl::extractMpanAndMeter);
        }

        @Override
        public CompletableFuture<List<ConsumptionRow>> getConsumption(String mpan, String meterSerial, Instant from, Instant to) {
            checkArgument(mpan != null && !mpan.isBlank(), "mpan must be non-blank");
            checkArgument(meterSerial != null && !meterSerial.isBlank(), "meterSerial must be non-blank");
            String url = BASE_URL + "/electricity-meter-points/" + mpan + "/meters/" + meterSerial + "/consumption/"
                         + "?page_size=" + MAX_PAGE_SIZE + "&period_from=" + from + "&period_to=" + to;
            // Half-hour slots — one row per slot.
            int expectedSlotCount = expectedHalfHourCount(from, to);
            // Consumption rows are personal data and the page URLs carry the MPAN and meter serial, so neither reaches a log line or a failure message.
            CompletableFuture<List<ConsumptionRow>> future = reportingHealth("get Octopus consumption",
                                                                             () -> paginate(pageUrl -> fetchConsumptionPage(pageUrl, mpan, meterSerial),
                                                                                            url,
                                                                                            expectedSlotCount,
                                                                                            ConsumptionPage::results,
                                                                                            ConsumptionPage::nextUrl));
            future.whenComplete(this::updateAuthStateFromOutcome);
            return future;
        }

        private CompletableFuture<ConsumptionPage> fetchConsumptionPage(String pageUrl, String mpan, String meterSerial) {
            StringFormattable loggablePageUrl = maskPersonalValues(pageUrl, mpan, meterSerial);
            return callSuppressingResponseBody(authedGetCall(pageUrl, loggablePageUrl), CONSUMPTION_PAGE_TYPE, DEFAULT_CALL_RETRY_COUNT, loggablePageUrl);
        }

        /// Subscribed to every authenticated call's outcome. On a 401 or 403 anywhere in the cause chain (the bound api key is no longer valid), transitions
        /// [#authState] to [AuthState.PermanentFailure]; on a successful response, to [AuthState.Success]. A transient failure (5xx, network) leaves the auth
        /// state unchanged: the bound credentials may still be valid; the call just couldn't get through. Pushes go through [#authStateUpdater] so
        /// identical-type transitions are deduped and subscribers only see real state changes.
        ///
        /// @param resultOrNull    the call's result, or `null` when the call failed
        /// @param throwableOrNull why the call failed, or `null` when it succeeded
        private void updateAuthStateFromOutcome(@Nullable Object resultOrNull, @Nullable Throwable throwableOrNull) {
            if (throwableOrNull == null) {
                authStateUpdater.accept(AuthState.Success.AUTHENTICATED);
                return;
            }
            if (HttpResponseException.findIn(throwableOrNull)
                                     .filter(httpResponse -> httpResponse.statusCode() == UNAUTHORIZED_401 || httpResponse.statusCode() == FORBIDDEN_403)
                                     .isPresent()) {
                authStateUpdater.accept(new AuthState.PermanentFailure(humanReadableMessage(throwableOrNull)));
            }
        }

        /// Builds a GET [Call] for a URL that requires this account's Basic-auth credentials; every authenticated Octopus endpoint takes the same
        /// `Authorization: Basic …` header.
        ///
        /// @param loggableUrl what the log line shows in place of `url`
        private Call authedGetCall(String url, StringFormattable loggableUrl) {
            return loggedCall(new Request.Builder()
                                      .url(url)
                                      .header("Authorization",
                                              "Basic " + base64().encode(key.apiKey().getBytes(StandardCharsets.US_ASCII)))
                                      .get()
                                      .build(),
                              loggableUrl);
        }

        @Override
        protected void doClose() {
            accountServices.remove(key, this);
        }
    }

    /// @return the number of half-hour slots that `[from, to)` touches, rounded up so that a size hint built from it never undershoots
    private static int expectedHalfHourCount(Instant from, Instant to) {
        return toIntExact(Duration.between(from, to).plus(HALF_HOUR).dividedBy(HALF_HOUR));
    }

    /// Qualifies the dependencies this connector consumes, so the bindings never collide with unannotated ones of the same type in the
    /// parent injector or in a sibling connector.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
