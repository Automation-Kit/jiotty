package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.net.SslCustomisation;
import okhttp3.OkHttpClient;
import okhttp3.Request;
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
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public final class TeslaFleetPartnerImpl extends BaseLifecycleComponent implements TeslaFleetPartner {
    private static final Logger logger = LogManager.getLogger(TeslaFleetPartnerImpl.class);

    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final AtomicInteger requestIdGenerator = new AtomicInteger();
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
        checkNotNull(baseUrl);
        this.sslCustomisation = sslCustomisation.orElse(null);
        registerPartnerDomainUrl = baseUrl + "/partner_accounts";
        partnerPublicKeyUrl = baseUrl + "/partner_accounts/public_key";
    }

    @Override
    protected void doStart() {
        httpClient = newClient(builder -> {
            if (sslCustomisation != null) {
                builder.sslSocketFactory(sslCustomisation.socketFactory(), sslCustomisation.trustManager());
            }
        });
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
                                                                   "{\"domain\": \"" + domain + "\"}",
                                                                   new TypeToken<ResponseWrapper<PartnerAccount>>() {})
                                                      .thenApply(TeslaHttp.unwrapOrFail())));
    }

    @Override
    public CompletableFuture<PartnerPublicKey> getPartnerPublicKey(String domain) {
        return whenStartedAndNotLifecycling(() -> acquirePartnerToken()
                .thenCompose(partnerToken -> {
                    String url = partnerPublicKeyUrl + "?domain=" + URLEncoder.encode(domain, UTF_8);
                    var request = new Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer " + partnerToken)
                            .get()
                            .build();
                    int requestId = requestIdGenerator.incrementAndGet();
                    logger.debug("[{}] executing GET {}", requestId, url);
                    return call(httpClient.newCall(request), new TypeToken<ResponseWrapper<PartnerPublicKey>>() {}, 0)
                            .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable))
                            .thenApply(TeslaHttp.unwrapOrFail());
                }));
    }

    private CompletableFuture<String> acquirePartnerToken() {
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
