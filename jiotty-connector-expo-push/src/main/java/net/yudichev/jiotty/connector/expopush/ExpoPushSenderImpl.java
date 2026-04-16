package net.yudichev.jiotty.connector.expopush;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Json;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.RestClients.call;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.common.security.LogRedaction.redact;

public class ExpoPushSenderImpl extends BaseLifecycleComponent implements ExpoPushSender {
    /// Maximum number of messages Expo accepts in a single `/push/send` request. Callers must not exceed this; [#send] rejects oversized lists.
    public static final int MAX_MESSAGES_PER_SEND = 100;
    /// Default base URL for Expo's push API; tests override via [BaseUrl].
    public static final String DEFAULT_BASE_URL = "https://exp.host";
    static final String SEND_PATH = "/--/api/v2/push/send";
    static final String GET_RECEIPTS_PATH = "/--/api/v2/push/getReceipts";
    private static final Logger logger = LogManager.getLogger(ExpoPushSenderImpl.class);
    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Duration RECEIPT_POLL_DELAY = Duration.ofMinutes(20);
    private static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";

    private final ExecutorFactory executorFactory;
    private final ExpoPushEventListener eventListener;
    private final Optional<String> accessToken;
    private final String sendUrl;
    private final String getReceiptsUrl;
    private OkHttpClient httpClient;
    private SchedulingExecutor executor;

    @Inject
    public ExpoPushSenderImpl(ExecutorFactory executorFactory,
                              ExpoPushEventListener eventListener,
                              @AccessToken Optional<String> accessToken,
                              @BaseUrl String baseUrl) {
        this.executorFactory = checkNotNull(executorFactory);
        this.eventListener = checkNotNull(eventListener);
        this.accessToken = checkNotNull(accessToken);
        checkNotNull(baseUrl);
        sendUrl = baseUrl + SEND_PATH;
        getReceiptsUrl = baseUrl + GET_RECEIPTS_PATH;
    }

    @Override
    protected void doStart() {
        httpClient = newClient();
        executor = executorFactory.createSingleThreadedSchedulingExecutor("ExpoPush");
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, executor, () -> shutdown(httpClient));
    }

    @Override
    public CompletableFuture<Void> send(List<ExpoPushMessage> messages) {
        checkArgument(messages.size() <= MAX_MESSAGES_PER_SEND,
                      "too many messages for a single send: %s (max %s)", messages.size(), MAX_MESSAGES_PER_SEND);
        return whenStartedAndNotLifecycling(() -> {
            if (messages.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return postJson(sendUrl, Json.stringify(messages), accessToken)
                    .thenAccept(response -> handleTicketsResponse(messages, response));
        });
    }

    private void handleTicketsResponse(List<ExpoPushMessage> messages, JsonNode response) {
        JsonNode data = response.get("data");
        if (data == null || !data.isArray() || data.size() != messages.size()) {
            eventListener.onUnexpectedError("Unexpected Expo send response shape for " + messages.size() + " messages: " + response);
            return;
        }
        Map<String, String> ticketIdToToken = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonNode ticket = data.get(i);
            String token = messages.get(i).token();
            String status = ticket.path("status").asText("");
            if ("ok".equals(status)) {
                String ticketId = ticket.path("id").asText(null);
                if (ticketId != null) {
                    ticketIdToToken.put(ticketId, token);
                }
            } else if ("error".equals(status)) {
                handleTokenError(token, ticket, "ticket");
            }
        }
        if (!ticketIdToToken.isEmpty()) {
            executor.schedule(RECEIPT_POLL_DELAY, () -> pollReceipts(ticketIdToToken));
        }
    }

    private void pollReceipts(Map<String, String> ticketIdToToken) {
        Map<String, ?> payload = Map.of("ids", ticketIdToToken.keySet());
        postJson(getReceiptsUrl, Json.stringify(payload), accessToken)
                .whenComplete((response, error) -> {
                    if (error != null) {
                        eventListener.onUnexpectedError("Expo receipts poll failed for " + ticketIdToToken.size() + " tickets: " + error);
                        return;
                    }
                    handleReceiptsResponse(ticketIdToToken, response);
                });
    }

    private void handleReceiptsResponse(Map<String, String> ticketIdToToken, JsonNode response) {
        JsonNode data = response.get("data");
        if (data == null || !data.isObject()) {
            eventListener.onUnexpectedError("Unexpected Expo receipts response shape: " + response);
            return;
        }
        ticketIdToToken.forEach((ticketId, token) -> {
            JsonNode receipt = data.get(ticketId);
            if (receipt == null) {
                return;
            }
            if ("error".equals(receipt.path("status").asText(""))) {
                handleTokenError(token, receipt, "receipt");
            }
        });
    }

    /// Test seam: posts `jsonBody` to `url` with optional bearer auth and parses the response as JSON. Overridden in tests to avoid real networking.
    protected CompletableFuture<JsonNode> postJson(String url, String jsonBody, Optional<String> bearerToken) {
        var body = RequestBody.create(jsonBody, MEDIA_TYPE_JSON);
        var requestBuilder = new Request.Builder().url(url).post(body);
        bearerToken.ifPresent(token -> requestBuilder.header("Authorization", "Bearer " + token));
        return call(httpClient.newCall(requestBuilder.build()), JsonNode.class);
    }

    private void handleTokenError(String token, JsonNode errorNode, String source) {
        String errorType = errorNode.path("details").path("error").asText("");
        if (DEVICE_NOT_REGISTERED.equals(errorType)) {
            eventListener.onDeadToken(token);
        } else {
            eventListener.onUnexpectedError("Expo push " + source + " error for token "
                                            + redact(token) + ": " + errorType
                                            + " — " + errorNode.path("message").asText(""));
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccessToken {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface BaseUrl {
    }
}
