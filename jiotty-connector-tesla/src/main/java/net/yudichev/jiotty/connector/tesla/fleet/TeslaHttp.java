package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.rest.ContentTypes;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

/// Tesla Fleet HTTP plumbing shared between [TeslaFleetImpl] (user/vehicle endpoints) and [TeslaFleetPartnerImpl]
/// (partner endpoints). Both connectors POST the same envelope shape and unwrap the same [ResponseWrapper], so the
/// request building and response unwrapping live here once.
final class TeslaHttp {
    static final String AUDIENCE = "https://fleet-api.prd.eu.vn.cloud.tesla.com";
    private static final Logger logger = LogManager.getLogger(TeslaHttp.class);
    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

    private TeslaHttp() {
    }

    static <T> Function<ResponseWrapper<T>, T> unwrapOrFail() {
        return responseWrapper -> responseWrapper.responseOrError()
                                                 .map(response -> response,
                                                      error -> {throw new RuntimeException(error);});
    }

    static <T> CompletableFuture<ResponseWrapper<T>> executePost(OkHttpClient httpClient,
                                                                 AtomicInteger requestIdGenerator,
                                                                 String url,
                                                                 String accessToken,
                                                                 @Nullable String jsonBody,
                                                                 TypeToken<ResponseWrapper<T>> responseType) {
        Request.Builder builder = new Request.Builder().url(url)
                                                       .header("Authorization", "Bearer " + accessToken);
        if (jsonBody == null) {
            builder.post(RequestBody.create(EMPTY_BYTE_ARRAY, MediaType.get(ContentTypes.CONTENT_TYPE_JSON)));
        } else {
            builder.post(RequestBody.create(jsonBody, MediaType.get(ContentTypes.CONTENT_TYPE_JSON)));
        }
        Request request = builder.build();
        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing POST {} {}", requestId, url, jsonBody == null ? "" : jsonBody);
        return call(httpClient.newCall(request), responseType, 0, true)
                .whenComplete((resp, throwable) -> logger.debug("[{}] result {}", requestId, resp, throwable));
    }

    /// Acquires a partner (client-credentials) token from the Tesla Fleet auth server. Uses a throwaway non-customised
    /// client because partner-token acquisition does not go through the (optionally) SSL-customised fleet endpoint.
    static CompletableFuture<String> acquirePartnerToken(AtomicInteger requestIdGenerator,
                                                         String clientId,
                                                         String clientSecret,
                                                         String scope) {
        RequestBody form = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("scope", scope)
                .add("audience", AUDIENCE)
                .build();

        var request = new Request.Builder()
                .url("https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token")
                .post(form)
                .build();

        int requestId = requestIdGenerator.incrementAndGet();
        logger.debug("[{}] executing {}", requestId, request.url());
        OkHttpClient partnerHttpClient = newClient(); // use non-customised generic client
        return call(partnerHttpClient.newCall(request), TokenResponse.class, 0)
                .whenComplete((resp, throwable) -> {
                    logger.debug("[{}] result {}", requestId, resp, throwable);
                    shutdown(partnerHttpClient);
                })
                .thenApply(TokenResponse::accessToken);
    }
}
