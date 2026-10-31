package net.yudichev.jiotty.connector.brevo;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CREATED_201;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.connector.brevo.BrevoClient.AccessCheckResult.ACCEPTED;
import static net.yudichev.jiotty.connector.brevo.BrevoClient.AccessCheckResult.KEY_REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class BrevoClientImplTest {
    private static final String BASE_URL = "https://brevo.test";
    private static final String API_KEY = "test-api-key";
    private static final String SUCCESS_BODY = """
                                               {"messageId": "<202608281200.1234567890@smtp-relay.mailin.fr>"}""";
    private static final String AGGREGATED_REPORT_BODY = """
                                                         {"range":"2026-10-31|2026-10-31","requests":3,"delivered":3,"hardBounces":0,"softBounces":0,\
                                                         "clicks":0,"uniqueClicks":0,"opens":1,"uniqueOpens":1,"spamReports":0,"blocked":0,"invalid":0,\
                                                         "unsubscribed":0}""";

    private final RecordingUpstreamHealthHandler healthHandler = new RecordingUpstreamHealthHandler();
    private final RecordingRetryableOperationExecutor retryExecutor = new RecordingRetryableOperationExecutor();
    /// Every request the client issued, so the test can assert on the URL, headers and body it built.
    private final List<Request> issuedRequests = new ArrayList<>();

    @Mock
    private OkHttpClient httpClient;
    /// Status every stubbed call responds with; `null` leaves calls pending, for tests that only care about the request being made.
    private @Nullable Integer stubbedStatus;
    /// Body every stubbed call responds with.
    private String stubbedBody = SUCCESS_BODY;
    private @Nullable BrevoClientImpl client;

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
    void buildsThePostWithTheApiKeyHeader() {
        createClient(healthHandler).sendEmail(email());

        assertThat(issuedRequests).singleElement().satisfies(issued -> {
            assertThat(issued.method()).isEqualTo("POST");
            assertThat(issued.url().toString()).isEqualTo(BASE_URL + BrevoClientImpl.SEND_EMAIL_PATH);
            assertThat(issued.header(BrevoClientImpl.API_KEY_HEADER)).isEqualTo(API_KEY);
        });
    }

    @Test
    void checkAccess_buildsAGetOfOneDaysStatisticsWithTheApiKeyHeader() {
        createClient(healthHandler).checkAccess();

        assertThat(issuedRequests).singleElement().satisfies(issued -> {
            assertThat(issued.method()).isEqualTo("GET");
            assertThat(issued.url().toString()).isEqualTo(BASE_URL + BrevoClientImpl.AGGREGATED_REPORT_PATH);
            assertThat(issued.header(BrevoClientImpl.API_KEY_HEADER)).isEqualTo(API_KEY);
            assertThat(issued.body()).isNull();
        });
        assertThat(retryExecutor.operationNames()).containsExactly(BrevoClientImpl.CHECK_ACCESS_OPERATION);
    }

    /// An accepted key is upstream health, so it clears an outage a failed send reported.
    @Test
    void checkAccess_acceptsTheKeyAndReportsHealthOnSuccess() {
        stubbedStatus = OK_200;
        stubbedBody = AGGREGATED_REPORT_BODY;

        assertThat(createClient(healthHandler).checkAccess()).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(ACCEPTED);
        assertThat(healthHandler.successCount()).isEqualTo(1);
    }

    /// The status decides the outcome, so a body of a shape the report never had is an accepted key, not a failure to retry.
    @Test
    void checkAccess_acceptsTheKeyWhateverTheBody() {
        stubbedStatus = OK_200;
        stubbedBody = "<html>maintenance</html>";

        assertThat(createClient(healthHandler).checkAccess()).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(ACCEPTED);
        assertThat(healthHandler.failures()).isEmpty();
    }

    /// A deactivated or IP-blocked key answers 401, and a key without the transactional email permission answers 403.
    @ParameterizedTest
    @ValueSource(ints = {UNAUTHORIZED_401, FORBIDDEN_403})
    void checkAccess_reportsARejectedKey(int status) {
        stubbedStatus = status;
        stubbedBody = """
                      {"code":"unauthorized","message":"Key not found"}""";

        assertThat(createClient(healthHandler).checkAccess()).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(KEY_REJECTED);
        assertThat(healthHandler.failures()).isEmpty();
    }

    @Test
    void checkAccess_serverError_failsAndReportsUpstreamFailure() {
        stubbedStatus = SERVICE_UNAVAILABLE_503;

        assertThat(createClient(healthHandler).checkAccess())
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .havingCause()
                .isInstanceOfSatisfying(HttpResponseException.class, cause -> assertThat(cause.statusCode()).isEqualTo(SERVICE_UNAVAILABLE_503));
        assertThat(healthHandler.failures()).hasSize(1);
    }

    /// Brevo's payload nests the sender and wraps recipients in an array; getting either shape wrong is accepted by nothing and rejected by Brevo.
    @Test
    void rendersBrevosNestedSenderAndRecipientShape() {
        assertThat(BrevoClientImpl.toWireJson(email())).isEqualTo(
                """
                {"sender":{"email":"no-reply@joulary.com","name":"Joulary"},\
                "to":[{"email":"someone@example.com","name":"Sam"}],\
                "subject":"Your Joulary account is ready",\
                "htmlContent":"<p>hello</p>",\
                "textContent":"hello"}""");
    }

    /// An absent recipient name must vanish from the payload rather than serialise as an explicit null, which Brevo rejects.
    @Test
    void omitsTheRecipientNameWhenAbsent() {
        String json = BrevoClientImpl.toWireJson(BrevoEmail.builder()
                                                           .setSenderName("Joulary")
                                                           .setSenderAddress("no-reply@joulary.com")
                                                           .setRecipientAddress("someone@example.com")
                                                           .setSubject("Your Joulary account is ready")
                                                           .setHtmlContent("<p>hello</p>")
                                                           .setTextContent("hello")
                                                           .build());

        assertThat(json).doesNotContain("name\":null").contains("\"to\":[{\"email\":\"someone@example.com\"}]");
    }

    @Test
    void completesOnAcceptance() {
        stubbedStatus = CREATED_201;

        assertThat(createClient(healthHandler).sendEmail(email())).succeedsWithin(Duration.ofSeconds(5));
    }

    /// The recipient's address and name render as the style's mask, so a value that reaches a log line or an exception message by accident carries neither.
    @Test
    void redactsTheRecipientInItsOwnRendering() {
        assertThat(email().toString()).doesNotContain("someone@example.com")
                                      .doesNotContain("Sam")
                                      .contains("no-reply@joulary.com");
    }

    /// Brevo quotes the offending address back in its rejection bodies, and that body must not survive into the exception a caller turns into an operator
    /// alert. The status must survive, because it is what decides whether the failure is retried.
    @Test
    void rejection_failsWithTheStatusButNeverTheRecipientAddress() {
        stubbedStatus = BAD_REQUEST_400;
        stubbedBody = """
                      {"code":"invalid_parameter","message":"someone@example.com is not a valid email address"}""";

        assertThat(createClient(healthHandler).sendEmail(email()))
                .failsWithin(Duration.ofSeconds(5))
                .withThrowableThat()
                .havingCause()
                .isInstanceOfSatisfying(HttpResponseException.class, cause -> {
                    assertThat(cause.statusCode()).isEqualTo(BAD_REQUEST_400);
                    assertThat(cause.body()).doesNotContain("someone@example.com");
                    assertThat(cause).hasMessageNotContaining("someone@example.com");
                });
    }

    /// Every call runs through the retry executor, so no path reaches the API without the shared-outage backoff in front of it.
    @Test
    void routesEveryCallThroughTheRetryExecutor() {
        createClient(healthHandler).sendEmail(email());

        assertThat(retryExecutor.operationNames()).containsExactly(BrevoClientImpl.SEND_EMAIL_OPERATION);
    }

    @Test
    void serverError_reportsUpstreamFailure() {
        stubbedStatus = SERVICE_UNAVAILABLE_503;

        createClient(healthHandler).sendEmail(email());

        assertThat(healthHandler.failures()).singleElement().satisfies(failure -> assertThat(failure).contains("Response code 503"));
        assertThat(healthHandler.successCount()).isZero();
    }

    /// A 400 is a verdict on this one message, not an outage every caller shares, so it must not be reported as upstream ill health.
    @Test
    void clientError_reportsNothing() {
        stubbedStatus = BAD_REQUEST_400;

        createClient(healthHandler).sendEmail(email());

        assertThat(healthHandler.failures()).isEmpty();
        assertThat(healthHandler.successCount()).isZero();
    }

    @Test
    void throwingHealthHandler_doesNotFailAnAcceptedSend() {
        stubbedStatus = CREATED_201;

        assertThat(createClient(new ThrowingUpstreamHealthHandler()).sendEmail(email())).succeedsWithin(Duration.ofSeconds(5));
    }

    /// A send arriving after the component stopped is dropped rather than throwing at the caller, which would surface a shutdown as that caller's fault.
    @Test
    void doesNotCompleteASendMadeAfterStopping() {
        stubbedStatus = CREATED_201;
        BrevoClientImpl stoppedClient = createClient(healthHandler);
        stoppedClient.stop();
        client = null;

        assertThat(stoppedClient.sendEmail(email())).isNotDone();
    }

    private static BrevoEmail email() {
        return BrevoEmail.builder()
                         .setSenderName("Joulary")
                         .setSenderAddress("no-reply@joulary.com")
                         .setRecipientAddress("someone@example.com")
                         .setRecipientName("Sam")
                         .setSubject("Your Joulary account is ready")
                         .setHtmlContent("<p>hello</p>")
                         .setTextContent("hello")
                         .build();
    }

    private BrevoClientImpl createClient(UpstreamHealthHandler handler) {
        client = new BrevoClientImpl(API_KEY, BASE_URL, Duration.ofSeconds(5), handler, retryExecutor) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        client.start();
        return client;
    }
}
