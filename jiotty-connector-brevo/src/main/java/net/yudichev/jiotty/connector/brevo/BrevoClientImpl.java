package net.yudichev.jiotty.connector.brevo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.misc.UpstreamHealthReporting.reportingHealth;
import static net.yudichev.jiotty.common.rest.RestClients.callSuppressingResponseBody;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;

public class BrevoClientImpl extends BaseLifecycleComponent implements BrevoClient {
    private static final Logger logger = LogManager.getLogger(BrevoClientImpl.class);

    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    /// [RestClients] is asked for no retries of its own: [#retryableOperationExecutor] already retries, and it does so selectively and with backoff, whereas
    /// the built-in count retries immediately and only on connection failures. Two retry loops would multiply.
    private static final int REST_CLIENT_RETRIES = 0;

    private final String apiKey;
    private final String baseUrl;
    private final Duration timeout;
    private final UpstreamHealthHandler healthHandler;
    private final RetryableOperationExecutor retryableOperationExecutor;

    private OkHttpClient client;

    @Inject
    public BrevoClientImpl(@ApiKey String apiKey,
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
    public CompletableFuture<Void> sendEmail(BrevoEmail email) {
        checkNotNull(email);
        var body = RequestBody.create(toWireJson(email), MEDIA_TYPE_JSON);
        var httpRequest = new Request.Builder().url(baseUrl + "/v3/smtp/email")
                                               .header("api-key", apiKey)
                                               .header("accept", "application/json")
                                               .post(body)
                                               .build();
        // The subject names the message kind and carries no address, which is what makes it the one field worth logging here.
        logger.debug("Sending email with subject '{}'", email.subject());
        return callApi(httpRequest);
    }

    /// Renders `email` into Brevo's wire shape. Package-private so a test can pin that shape — the nesting of `sender` and the `to` array is the part Brevo
    /// silently rejects when it is wrong, and reading it back off the built [RequestBody] would need an okio dependency this module otherwise has no use for.
    @VisibleForTesting
    static String toWireJson(BrevoEmail email) {
        return Json.stringify(new SendEmailRequest(new Party(email.senderAddress(), email.senderName()),
                                                   ImmutableList.of(new Party(email.recipientAddress(), email.recipientName().orElse(null))),
                                                   email.subject(),
                                                   email.htmlContent(),
                                                   email.textContent()));
    }

    /// Runs the call through [UpstreamHealthReporting#reportingHealth], so one sustained Brevo outage is reported once rather than once per message.
    private CompletableFuture<Void> callApi(Request httpRequest) {
        return whenNotLifecycling(() -> {
            if (!isStartedPlain()) {
                return new CompletableFuture<>();
            }
            // callSuppressingResponseBody, not call: Brevo quotes the offending value back in its rejection bodies, so a rejected recipient comes back as a
            // message naming that address. Suppression keeps it out of the DEBUG log line, out of HttpResponseException, and so out of the failure alert the
            // caller raises from it — while leaving the status code, which is what decides whether the failure is retried.
            return reportingHealth(retryableOperationExecutor, healthHandler, "brevo.smtp.email", "Brevo transactional email call failed",
                                   () -> callSuppressingResponseBody(client.newCall(httpRequest), SendEmailResponse.class, REST_CLIENT_RETRIES))
                    .thenApply(_ -> null);
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

    /// Brevo's `POST /v3/smtp/email` payload. [JsonInclude.Include#NON_NULL] keeps an absent recipient name out of the JSON entirely — Brevo rejects an
    /// explicit `null` there.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record SendEmailRequest(Party sender, List<Party> to, String subject, String htmlContent, String textContent) {}

    /// One end of the message — the sender, or a recipient.
    ///
    /// @param email the address
    /// @param name  the display name to show beside it; `null` when none is on file, which keeps the field out of the JSON altogether
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Party(String email, @Nullable String name) {}

    /// Brevo answers an accepted message with its queue id, which this client discards. Parsed rather than ignored so that a shape change surfaces as a
    /// parse failure instead of passing silently.
    ///
    /// @param messageId `null` if Brevo ever stops sending one
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SendEmailResponse(@Nullable String messageId) {}

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
