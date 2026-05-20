package net.yudichev.jiotty.connector.octopusenergy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ConcurrentDeduplicatingConsumer;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.security.AuthState;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.io.BaseEncoding.base64;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.paginate;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

/// [Guide 1](https://octopus.energy/blog/agile-smart-home-diy/), [Guide 2](https://www.guylipman.com/octopus/api_guide.html)
@SuppressWarnings("WeakerAccess") // public outer surface; see java-style "internal APIs on non-public types" rule (applies even though this class is public)
public class OctopusEnergyImpl extends BaseLifecycleComponent implements OctopusEnergy {
    static final String BASE_URL = "https://api.octopus.energy/v1";
    static final AuthState.Success AUTH_SUCCESS = new AuthState.Success("authenticated");
    /// Octopus's `/products/` catalogue ~92 entries at time of writing (empirical from a live capture). The constant is a sizing hint for the paginate
    /// accumulator — overshooting is harmless, and the catalogue grows much more slowly than the connector's release cadence.
    private static final int EXPECTED_PRODUCT_COUNT = 265;
    /// Octopus rates / standing charges arrive in half-hour slots, so this is the divisor that converts a `(from, to)` window into a slot count.
    private static final Duration HALF_HOUR = Duration.ofMinutes(30);
    private static final Logger logger = LogManager.getLogger(OctopusEnergyImpl.class);

    private final Map<Character, RegionServiceImpl> regionServices = new ConcurrentHashMap<>();
    private final Map<AccountKey, AccountServiceImpl> accountServices = new ConcurrentHashMap<>();

    private OkHttpClient client;

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
        return paginate(this::openGetCall, BASE_URL + "/products/?available_at=" + availableAt,
                        EXPECTED_PRODUCT_COUNT,
                        new TypeToken<>() {}, ProductsPage::results, ProductsPage::nextUrl);
    }

    @Override
    public CompletableFuture<ProductDetails> getProductDetails(String code) {
        return call(openGetCall(BASE_URL + "/products/" + code + "/"), new TypeToken<>() {});
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
        return loggedCall(new Request.Builder().url(url).get().build());
    }

    /// Wraps `client.newCall(request)` with a single trace-log line, so every code path that turns a built [Request] into a [Call] logs the same shape.
    private Call loggedCall(Request request) {
        logger.debug("Calling {}", request.url());
        return client.newCall(request);
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
            if (tariffRegionMismatch(tariffCode)) {
                return CompletableFuture.failedFuture(tariffRegionMismatchError(tariffCode));
            }
            String url = tariffsUrl(productCode, tariffCode) + "standard-unit-rates/?period_from=" + from + "&period_to=" + to;
            // Half-hour slots — one rate per slot. Round up so the hint never undershoots; one slot of headroom is cheap.
            int expectedSlotCount = Math.toIntExact(Duration.between(from, to).plus(HALF_HOUR).dividedBy(HALF_HOUR));
            return paginate(OctopusEnergyImpl.this::openGetCall,
                            url,
                            expectedSlotCount,
                            new TypeToken<>() {},
                            StandardUnitRates::rates,
                            StandardUnitRates::nextUrl);
        }

        @Override
        public CompletableFuture<List<StandingCharge>> getStandingCharges(String productCode, String tariffCode, Instant from, Instant to) {
            if (tariffRegionMismatch(tariffCode)) {
                return CompletableFuture.failedFuture(tariffRegionMismatchError(tariffCode));
            }
            String url = tariffsUrl(productCode, tariffCode) + "standing-charges/?period_from=" + from + "&period_to=" + to;

            return paginate(OctopusEnergyImpl.this::openGetCall,
                            url,
                            4, // Standing charges change at most a handful of times per tariff per year; a small hint is enough — overshoot is cheap.
                            new TypeToken<>() {},
                            StandingChargesPage::results,
                            StandingChargesPage::nextUrl);
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
        private final ObservableValue<AuthState> authState = ObservableValue.concurrent(new AuthState.TransientFailure("Initialising"));
        /// Forwards into [#authState] only on a *type-level* transition — `Success → Success` and `PermanentFailure → PermanentFailure` are deduped so
        /// subscribers aren't spammed by every successful authenticated call. The CAS inside [ConcurrentDeduplicatingConsumer] also makes the concurrent case
        /// (two `whenComplete` callbacks landing on different dispatcher threads) safe.
        private final Consumer<AuthState> authStateUpdater = new ConcurrentDeduplicatingConsumer<>(
                (previous, next) -> previous.getClass() == next.getClass(), authState);
        private final CompletableFuture<OctopusAccountData> accountFuture;

        AccountServiceImpl(AccountKey key) {
            this.key = key;
            accountFuture = call(authedGetCall(BASE_URL + "/accounts/" + key.accountId()), new TypeToken<>() {});
            accountFuture.whenComplete(this::updateAuthStateFromOutcome);
        }

        @Override
        public Closeable subscribeToAuthState(Consumer<AuthState> handler) {
            return authState.subscribe(handler);
        }

        @Override
        public CompletableFuture<OctopusAccountData> getAccount() {
            return accountFuture;
        }

        @Override
        public CompletableFuture<List<MpanAndMeter>> getMpanAndMeter() {
            return accountFuture.thenApply(OctopusEnergyImpl::extractMpanAndMeter);
        }

        @Override
        public CompletableFuture<List<ConsumptionRow>> getConsumption(String mpan, String meterSerial, Instant from, Instant to) {
            checkArgument(mpan != null && !mpan.isBlank(), "mpan must be non-blank");
            checkArgument(meterSerial != null && !meterSerial.isBlank(), "meterSerial must be non-blank");
            String url = BASE_URL + "/electricity-meter-points/" + mpan + "/meters/" + meterSerial + "/consumption/"
                         + "?period_from=" + from + "&period_to=" + to;
            // Half-hour slots — one row per slot. Round up so the hint never undershoots.
            int expectedSlotCount = Math.toIntExact(Duration.between(from, to).plus(HALF_HOUR).dividedBy(HALF_HOUR));
            CompletableFuture<List<ConsumptionRow>> future = paginate(this::authedGetCall,
                                                                      url,
                                                                      expectedSlotCount,
                                                                      new TypeToken<>() {},
                                                                      ConsumptionPage::results,
                                                                      ConsumptionPage::nextUrl);
            future.whenComplete(this::updateAuthStateFromOutcome);
            return future;
        }

        /// Subscribed at the tail of every authenticated call's outcome. On a 401 or 403 anywhere in the cause chain (the bound api key is no longer valid),
        /// transitions [#authState] to [AuthState.PermanentFailure]. On a successful response, transitions to [AuthState.Success]. Anything else (5xx, network)
        /// is a no-op — per the plan, transient failures don't touch the auth state. Pushes go through [#authStateUpdater] so identical-type transitions are
        /// deduped and subscribers only see real state changes.
        private void updateAuthStateFromOutcome(Object resultOrNull, Throwable throwableOrNull) {
            if (throwableOrNull == null) {
                authStateUpdater.accept(AUTH_SUCCESS);
                return;
            }
            logger.info("Authenticated call failed", throwableOrNull);
            for (Throwable cur = throwableOrNull; cur != null; cur = cur.getCause()) {
                if (cur instanceof HttpResponseException http && (http.statusCode() == 401 || http.statusCode() == 403)) {
                    authStateUpdater.accept(new AuthState.PermanentFailure(humanReadableMessage(throwableOrNull)));
                    return;
                }
            }
            // Transient/network failures don't change the auth state — the bound credentials may still be valid; the call just couldn't get through.
        }

        /// Builds a GET [Call] for a URL that requires this account's Basic-auth credentials. Used both for the initial `/accounts/{id}` fetch and for
        /// `getConsumption(...)` — every authenticated Octopus endpoint takes the same `Authorization: Basic …` header.
        private Call authedGetCall(String url) {
            return loggedCall(new Request.Builder()
                                      .url(url)
                                      .header("Authorization",
                                              "Basic " + base64().encode(key.apiKey().getBytes(StandardCharsets.US_ASCII)))
                                      .get()
                                      .build());
        }

        @Override
        protected void doClose() {
            accountServices.remove(key, this);
        }
    }
}
