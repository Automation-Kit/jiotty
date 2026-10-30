package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.net.SslCustomisation;
import net.yudichev.jiotty.common.rest.ContentTypes;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isClientError;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// Tesla Fleet HTTP plumbing shared between [TeslaFleetImpl] (user/vehicle endpoints) and [TeslaFleetPartnerImpl]
/// (partner endpoints). Both connectors POST the same envelope shape and unwrap the same [ResponseWrapper], so the
/// request building and response unwrapping live here once.
final class TeslaHttp {
    public static final String AUDIENCE = "https://fleet-api.prd.eu.vn.cloud.tesla.com";
    public static final String TOKEN_URL = "https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token";
    private static final Logger logger = LogManager.getLogger(TeslaHttp.class);
    /// A VIN is 17 characters from an alphabet that excludes I, O and Q, so it cannot collide with a hex token of the same length.
    private static final Pattern VIN = Pattern.compile("\\b[A-HJ-NPR-Z0-9]{17}\\b");
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get(ContentTypes.CONTENT_TYPE_JSON);
    private static final RequestBody EMPTY_JSON_BODY = RequestBody.create(new byte[0], JSON_MEDIA_TYPE);

    private TeslaHttp() {
    }

    public static OkHttpClient newFleetClient(Optional<SslCustomisation> sslCustomisation) {
        return newClient(builder -> sslCustomisation.ifPresent(customisation -> builder.sslSocketFactory(customisation.socketFactory(),
                                                                                                         customisation.trustManager())));
    }

    public static String telemetryConfigUrl(String baseUrl, String vin) {
        return baseUrl + "/vehicles/" + vin + "/fleet_telemetry_config";
    }

    /// A request to `url` that carries `token` as its bearer credential.
    public static Request.Builder newAuthorisedRequest(String url, String token) {
        return new Request.Builder().url(url).header(AUTHORIZATION, BEARER_PREFIX + token);
    }

    public static <T> Function<ResponseWrapper<T>, T> unwrapOrFail() {
        return responseWrapper -> responseWrapper.responseOrError()
                                                 .map(response -> response,
                                                      error -> {throw new RuntimeException(error);});
    }

    /// @param <T>      the type of the envelope's response
    /// @param jsonBody the request's JSON payload, empty to post an empty body
    public static <T> CompletableFuture<ResponseWrapper<T>> executePost(OkHttpClient httpClient,
                                                                        AtomicInteger requestIdGenerator,
                                                                        String url,
                                                                        String accessToken,
                                                                        Optional<String> jsonBody,
                                                                        TypeToken<ResponseWrapper<T>> responseType) {
        RequestBody body = jsonBody.map(json -> RequestBody.create(json, JSON_MEDIA_TYPE)).orElse(EMPTY_JSON_BODY);
        Request request = newAuthorisedRequest(url, accessToken).post(body).build();
        int requestId = requestIdGenerator.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] executing POST {} {}", requestId, withVinsRedacted(url), jsonBody.isPresent() ? withVinsRedacted(jsonBody.get()) : "");
        }
        return callParsingErrorEnvelope(httpClient.newCall(request), responseType).whenComplete(logResult(logger, requestId));
    }

    /// Completes with the parsed envelope of a successful response, and of a client-error one whose body is a Fleet envelope, because Tesla reports domain
    /// refusals such as an offline vehicle in a 4xx `{"error": …}` envelope. A rejected credential, a rate limit, a server error and any other unsuccessful
    /// body fail the call with their status.
    ///
    /// @param <T> the type of the envelope's response
    public static <T> CompletableFuture<ResponseWrapper<T>> callParsingErrorEnvelope(Call theCall, TypeToken<ResponseWrapper<T>> responseType) {
        return callRedactingVins(theCall, responseType)
                .exceptionallyCompose(failure -> HttpResponseException.findIn(failure)
                                                                      .filter(httpFailure -> isClientError(httpFailure.statusCode())
                                                                                             && httpFailure.statusCode() != UNAUTHORIZED_401
                                                                                             && httpFailure.statusCode() != TOO_MANY_REQUESTS_429)
                                                                      .map(httpFailure -> parseEnvelope(httpFailure.body(), responseType))
                                                                      .map(CompletableFuture::completedFuture)
                                                                      .orElseGet(() -> CompletableFuture.failedFuture(failure)));
    }

    /// Calls with every VIN in the request's URL redacted from the call's log lines and failure messages, which reach logs and operator alerts.
    ///
    /// @param <T> the type of the call's value
    public static <T> CompletableFuture<T> callRedactingVins(Call theCall, TypeToken<? extends T> responseType) {
        return call(theCall, responseType, 0, withVinsRedacted(theCall.request().url().toString()));
    }

    /// @param <T> the type of the envelope's response
    /// @return the envelope `body` holds, or `null` when it is not JSON, or is JSON carrying neither a response nor an error
    private static <T> @Nullable ResponseWrapper<T> parseEnvelope(String body, TypeToken<ResponseWrapper<T>> responseType) {
        try {
            ResponseWrapper<T> envelope = Json.parse(body, responseType);
            return envelope.response().isPresent() || envelope.error().isPresent() ? envelope : null;
        } catch (RuntimeException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unsuccessful response body is not a Fleet envelope: {}", humanReadableMessageFormattable(e));
            }
            return null;
        }
    }

    /// Acquires a partner (client-credentials) token from the Tesla Fleet auth server. Uses a throwaway non-customised
    /// client because partner-token acquisition does not go through the (optionally) SSL-customised fleet endpoint.
    public static CompletableFuture<String> acquirePartnerToken(AtomicInteger requestIdGenerator,
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
                .url(TOKEN_URL)
                .post(form)
                .build();

        int requestId = startLoggedRequest(logger, requestIdGenerator, "POST", TOKEN_URL);
        OkHttpClient partnerHttpClient = newClient(); // use non-customised generic client
        return call(partnerHttpClient.newCall(request), TokenResponse.class, 0)
                .whenComplete(logResult(logger, requestId))
                .whenComplete((_, _) -> shutdown(partnerHttpClient))
                .thenApply(TokenResponse::accessToken);
    }

    /// Logs to `logger` at DEBUG that a `method` request to `url` is starting, with every VIN in `url` redacted.
    ///
    /// @return the id the request's later log lines carry
    public static int startLoggedRequest(Logger logger, AtomicInteger requestIdGenerator, String method, String url) {
        int requestId = requestIdGenerator.incrementAndGet();
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] executing {} {}", requestId, method, withVinsRedacted(url));
        }
        return requestId;
    }

    /// @param <T>       the type of the call's value
    /// @param requestId the id the request's earlier log lines carry
    /// @return a [CompletableFuture#whenComplete] callback that logs the call's outcome to `logger` at DEBUG; its failure argument is `null` when the call
    ///         succeeded
    public static <T> BiConsumer<@Nullable T, @Nullable Throwable> logResult(Logger logger, int requestId) {
        return (result, failure) -> logger.debug("[{}] result {}", requestId, result, failure);
    }

    /// A rendering of `text` with every VIN reduced to its first three characters, deferred to the logging thread. The Tesla fleet API carries the VIN in
    /// request paths and in a `vins` array, and a VIN identifies the vehicle and so its keeper.
    public static StringFormattable withVinsRedacted(String text) {
        return appendable -> {
            var matcher = VIN.matcher(text);
            var appendedTo = 0;
            while (matcher.find()) {
                Append.to(appendable, text, appendedTo, matcher.start());
                appendRedacted(appendable, text, matcher.start(), matcher.end());
                appendedTo = matcher.end();
            }
            Append.to(appendable, text, appendedTo, text.length());
        };
    }
}
