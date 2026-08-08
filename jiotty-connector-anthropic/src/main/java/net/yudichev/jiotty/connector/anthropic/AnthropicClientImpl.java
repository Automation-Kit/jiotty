package net.yudichev.jiotty.connector.anthropic;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthReporting;
import net.yudichev.jiotty.common.rest.RestClients;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.misc.UpstreamHealthReporting.reportingHealth;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public class AnthropicClientImpl extends BaseLifecycleComponent implements AnthropicClient {
    private static final Logger logger = LogManager.getLogger(AnthropicClientImpl.class);

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    /// Pinned rather than tracking "latest": Anthropic versions the wire format by date, and an unannounced shape change would break parsing in production.
    private static final String ANTHROPIC_WIRE_FORMAT_VERSION = "2023-06-01";
    /// [RestClients] is asked for no retries of its own: [#retryableOperationExecutor] already retries, and it does so selectively (only shared-outage
    /// statuses) and with backoff, whereas the built-in count retries immediately and only on connection failures. Two retry loops would multiply.
    private static final int REST_CLIENT_RETRIES = 0;

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final UpstreamHealthHandler healthHandler;
    /// Rides out the transient shared-outage failures (`overloaded_error`, a dropped connection) that every caller of this API sees at once, so only a
    /// sustained outage reaches [#healthHandler] — and, because a user is waiting on the reply, over a deliberately short window.
    private final RetryableOperationExecutor retryableOperationExecutor;

    private OkHttpClient client;

    @Inject
    public AnthropicClientImpl(@ApiKey String apiKey,
                               @BaseUrl String baseUrl,
                               @Timeout Duration timeout,
                               @Dependency UpstreamHealthHandler healthHandler,
                               @Dependency RetryableOperationExecutor retryableOperationExecutor) {
        this.apiKey = checkNotNull(apiKey);
        this.baseUrl = checkNotNull(baseUrl);
        this.timeout = checkNotNull(timeout);
        this.healthHandler = checkNotNull(healthHandler);
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
    }

    @Override
    protected void doStart() {
        client = createHttpClient();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, () -> shutdown(client));
    }

    @Override
    public CompletableFuture<MessagesResponse> sendMessage(MessagesRequest request) {
        var body = RequestBody.create(Json.stringify(request), MEDIA_TYPE_JSON);
        var httpRequest = new Request.Builder().url(baseUrl + "/v1/messages")
                                               .header("x-api-key", apiKey)
                                               .header("anthropic-version", ANTHROPIC_WIRE_FORMAT_VERSION)
                                               .post(body)
                                               .build();
        if (logger.isDebugEnabled()) {
            logger.debug("Sending {} message(s) to model {}", request.messages().size(), request.model());
        }
        return callApi(httpRequest);
    }

    /// Runs the call through [UpstreamHealthReporting#reportingHealth], so one sustained Anthropic outage is reported once however many callers the shared
    /// client serves, instead of once per user question.
    private CompletableFuture<MessagesResponse> callApi(Request httpRequest) {
        return whenNotLifecycling(() -> {
            if (!isStarted()) {
                // Reported through the future by never completing it: a caller draining onto a stopped component has no fault to report, and throwing here
                // would surface on its thread instead.
                return new CompletableFuture<>();
            }
            return reportingHealth(retryableOperationExecutor, healthHandler, "anthropic.messages", "Anthropic Messages API call failed",
                                   () -> call(client.newCall(httpRequest), MessagesResponse.class, REST_CLIENT_RETRIES));
        });
    }

    /// Builds the HTTP client this component owns. Overridable — and the reason this class is not final — so a test can substitute one that answers without
    /// networking.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient(builder -> builder.connectTimeout(timeout)
                                           .callTimeout(timeout)
                                           .readTimeout(timeout)
                                           .writeTimeout(timeout));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiKey {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface BaseUrl {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Timeout {
    }

    /// Qualifies the dependencies this client consumes, so the bindings never collide with unannotated ones of the same type in the parent injector or in a
    /// sibling connector.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
