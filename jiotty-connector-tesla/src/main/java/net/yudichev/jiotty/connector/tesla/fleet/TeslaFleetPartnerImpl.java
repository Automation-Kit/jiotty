package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.net.SslCustomisation;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.URLEncoder;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.callSuppressingResponseBody;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaHttp.logResult;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaHttp.newAuthorisedRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaHttp.startLoggedRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaHttp.withVinsRedacted;

public class TeslaFleetPartnerImpl extends BaseLifecycleComponent implements TeslaFleetPartner {
    @VisibleForTesting
    static final String PARTNER_ACCOUNTS_PATH = "/partner_accounts";
    @VisibleForTesting
    static final String PARTNER_PUBLIC_KEY_PATH = PARTNER_ACCOUNTS_PATH + "/public_key";

    private static final Logger logger = LogManager.getLogger(TeslaFleetPartnerImpl.class);

    private static final TypeToken<ResponseWrapper<TelemetryDeleteConfigResponse>> DELETE_CONFIG_RESPONSE_TYPE = new TypeToken<>() {};

    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final AtomicInteger requestIdGenerator = new AtomicInteger();
    private final String baseUrl;
    private final String registerPartnerDomainUrl;
    private final String partnerPublicKeyUrl;
    private final @Nullable SslCustomisation sslCustomisation;
    private OkHttpClient httpClient;

    @Inject
    public TeslaFleetPartnerImpl(@ClientId String clientId,
                                 @ClientSecret String clientSecret,
                                 @Scope String scope,
                                 @BaseUrl String baseUrl,
                                 @Dependency Optional<SslCustomisation> sslCustomisation) {
        this.clientId = checkNotNull(clientId);
        this.clientSecret = checkNotNull(clientSecret);
        this.scope = checkNotNull(scope);
        this.baseUrl = checkNotNull(baseUrl);
        this.sslCustomisation = sslCustomisation.orElse(null);
        registerPartnerDomainUrl = baseUrl + PARTNER_ACCOUNTS_PATH;
        partnerPublicKeyUrl = baseUrl + PARTNER_PUBLIC_KEY_PATH;
    }

    @Override
    protected void doStart() {
        httpClient = createHttpClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(httpClient));
    }

    @Override
    public CompletableFuture<PartnerAccount> registerPartnerDomain(String domain) {
        return whenStartedAndNotLifecycling(() -> acquirePartnerToken()
                .thenCompose(partnerToken -> TeslaHttp.executePost(httpClient,
                                                                   requestIdGenerator,
                                                                   registerPartnerDomainUrl,
                                                                   partnerToken,
                                                                   Optional.of("{\"domain\": \"" + domain + "\"}"),
                                                                   new TypeToken<ResponseWrapper<PartnerAccount>>() {})
                                                      .thenApply(TeslaHttp.unwrapOrFail())));
    }

    @Override
    public CompletableFuture<PartnerPublicKey> getPartnerPublicKey(String domain) {
        return whenStartedAndNotLifecycling(() -> acquirePartnerToken()
                .thenCompose(partnerToken -> {
                    String url = partnerPublicKeyUrl + "?domain=" + URLEncoder.encode(domain, UTF_8);
                    var request = newAuthorisedRequest(url, partnerToken).get().build();
                    int requestId = startLoggedRequest(logger, requestIdGenerator, "GET", url);
                    return call(httpClient.newCall(request), new TypeToken<ResponseWrapper<PartnerPublicKey>>() {}, 0)
                            .whenComplete(logResult(logger, requestId))
                            .thenApply(TeslaHttp.unwrapOrFail());
                }));
    }

    @Override
    public CompletableFuture<TelemetryDeleteConfigResponse> deleteTelemetryConfig(String vin) {
        return whenStartedAndNotLifecycling(() -> acquirePartnerToken()
                .thenCompose(partnerToken -> {
                    String url = TeslaHttp.telemetryConfigUrl(baseUrl, vin);
                    var request = newAuthorisedRequest(url, partnerToken).delete().build();
                    int requestId = startLoggedRequest(logger, requestIdGenerator, "DELETE", url);
                    return callSuppressingResponseBody(httpClient.newCall(request), DELETE_CONFIG_RESPONSE_TYPE, 0, withVinsRedacted(url))
                            .whenComplete(logResult(logger, requestId))
                            .thenApply(TeslaHttp.unwrapOrFail());
                }));
    }

    /// Creates the client every partner call goes through. Overridden in tests to inject a deterministic fake.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return TeslaHttp.newFleetClient(Optional.ofNullable(sslCustomisation));
    }

    /// Overridden in tests, which have no Tesla auth server to obtain a token from.
    @VisibleForTesting
    CompletableFuture<String> acquirePartnerToken() {
        return TeslaHttp.acquirePartnerToken(requestIdGenerator, clientId, clientSecret, scope);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientId {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientSecret {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Scope {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface BaseUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
