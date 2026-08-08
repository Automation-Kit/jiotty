package net.yudichev.jiotty.connector.anthropic;

import net.yudichev.jiotty.common.async.backoff.RecordingRetryableOperationExecutor;
import net.yudichev.jiotty.common.misc.RecordingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.ThrowingUpstreamHealthHandler;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.connector.anthropic.MessagesResponses.textOf;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AnthropicClientImplTest {
    private static final String BASE_URL = "https://anthropic.test";
    private static final String API_KEY = "test-api-key";
    private static final String SUCCESS_BODY = """
                                               {"content": [{"type": "text", "text": "hi"}], "stop_reason": "end_turn", "usage": {"input_tokens": 3, "output_tokens": 4}}""";

    private final RecordingUpstreamHealthHandler healthHandler = new RecordingUpstreamHealthHandler();
    private final RecordingRetryableOperationExecutor retryExecutor = new RecordingRetryableOperationExecutor();
    /// Every request the client issued, so the test can assert on the URL and headers it built.
    private final List<Request> issuedRequests = new ArrayList<>();

    @Mock
    private OkHttpClient httpClient;
    /// Status and body every stubbed call responds with; a `null` status leaves calls pending, for tests that only care about the request being made.
    private @Nullable Integer stubbedStatus;
    private String stubbedBody = SUCCESS_BODY;
    private @Nullable AnthropicClientImpl client;

    @BeforeEach
    void setUp() {
        stubCalls(httpClient, request -> {
            issuedRequests.add(request);
            return stubbedStatus == null ? null : response(request, stubbedStatus, stubbedBody);
        });
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
    }

    /// The API key travels in a header and must never end up in the body or the URL, where it would reach request logs and error messages.
    @Test
    void buildsThePostWithTheApiKeyAndVersionHeaders() {
        createClient(healthHandler).sendMessage(request());

        assertThat(issuedRequests).singleElement().satisfies(issued -> {
            assertThat(issued.method()).isEqualTo("POST");
            assertThat(issued.url().toString()).isEqualTo(BASE_URL + "/v1/messages");
            assertThat(issued.header("x-api-key")).isEqualTo(API_KEY);
            assertThat(issued.header("anthropic-version")).isEqualTo("2023-06-01");
        });
    }

    @Test
    void parsesASuccessfulReply() throws Exception {
        stubbedStatus = OK_200;

        MessagesResponse response = createClient(healthHandler).sendMessage(request()).get(5, SECONDS);

        assertThat(textOf(response)).isEqualTo("hi");
        assertThat(response.isCompleteTurn()).isTrue();
        assertThat(response.usage().inputTokens()).isEqualTo(3L);
    }

    /// The service's own error envelope is the most useful thing about a rejected call, so it must survive into the exception rather than being replaced by a
    /// bare status code — and the call must fail, not come back as an empty-looking success.
    @Test
    void failsWithTheServiceErrorBodyOnANonSuccessStatus() {
        stubbedStatus = BAD_REQUEST_400;
        stubbedBody = """
                      {"type": "error", "error": {"type": "invalid_request_error", "message": "max_tokens is too large"}}""";

        assertThat(createClient(healthHandler).sendMessage(request()))
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .havingCause()
                .isInstanceOfSatisfying(HttpResponseException.class, cause -> {
                    assertThat(cause.statusCode()).isEqualTo(BAD_REQUEST_400);
                    assertThat(cause).hasMessageContaining("max_tokens is too large");
                });
    }

    /// Every call runs through the retry executor, so no path reaches the API without the shared-outage backoff in front of it.
    @Test
    void routesEveryCallThroughTheRetryExecutor() {
        createClient(healthHandler).sendMessage(request());

        assertThat(retryExecutor.operationNames()).containsExactly("anthropic.messages");
    }

    @Test
    void serverError_reportsUpstreamFailure() {
        stubbedStatus = SERVICE_UNAVAILABLE_503;

        createClient(healthHandler).sendMessage(request());

        assertThat(healthHandler.failures()).singleElement().satisfies(failure -> assertThat(failure).contains("Response code 503"));
        assertThat(healthHandler.successCount()).isZero();
    }

    /// A 400 is a verdict on this one request, not an outage every caller shares, so it must not be reported as upstream ill health.
    @Test
    void clientError_reportsNothing() {
        stubbedStatus = BAD_REQUEST_400;

        createClient(healthHandler).sendMessage(request());

        assertThat(healthHandler.failures()).isEmpty();
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void throwingHealthHandler_doesNotFailASuccessfulCall() throws Exception {
        stubbedStatus = OK_200;

        assertThat(textOf(createClient(new ThrowingUpstreamHealthHandler()).sendMessage(request()).get(5, SECONDS))).isEqualTo("hi");
    }

    /// A call arriving after the component stopped is dropped rather than throwing at the caller, which would surface a shutdown as that caller's fault.
    @Test
    void doesNotCompleteACallMadeAfterStopping() {
        stubbedStatus = OK_200;
        AnthropicClientImpl stoppedClient = createClient(healthHandler);
        stoppedClient.stop();
        client = null;

        assertThat(stoppedClient.sendMessage(request())).isNotDone();
    }

    private static MessagesRequest request() {
        return MessagesRequest.builder()
                              .setModel("test-model")
                              .setMaxTokens(16)
                              .addMessages(Message.of(Role.USER, "q"))
                              .build();
    }

    private AnthropicClientImpl createClient(UpstreamHealthHandler handler) {
        client = new AnthropicClientImpl(API_KEY, BASE_URL, Duration.ofSeconds(5), handler, retryExecutor) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        client.start();
        return client;
    }
}
