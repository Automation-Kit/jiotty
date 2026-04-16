package net.yudichev.jiotty.connector.expopush;

import com.fasterxml.jackson.databind.JsonNode;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.connector.expopush.ExpoPushSenderImpl.GET_RECEIPTS_PATH;
import static net.yudichev.jiotty.connector.expopush.ExpoPushSenderImpl.MAX_MESSAGES_PER_SEND;
import static net.yudichev.jiotty.connector.expopush.ExpoPushSenderImpl.SEND_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ExpoPushSenderImplTest {
    private static final String TOKEN_A = "ExponentPushToken[aaa]";
    private static final String TOKEN_B = "ExponentPushToken[bbb]";
    private static final String BASE_URL = "http://expo.test";
    private static final Duration NO_WAIT = Duration.ZERO;
    private static final Instant START = Instant.parse("2026-04-15T10:00:00Z");

    @Mock
    private ExpoPushEventListener listener;
    @Captor
    private ArgumentCaptor<String> errorCaptor;

    private ProgrammableClock clock;
    private List<CapturedRequest> capturedRequests;
    private Deque<CompletableFuture<JsonNode>> cannedResponses;
    private ExpoPushSenderImpl sender;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock().withMdc();
        clock.setTimeAndTick(START);
        capturedRequests = new ArrayList<>();
        cannedResponses = new ArrayDeque<>();
    }

    @AfterEach
    void tearDown() {
        if (sender != null) {
            sender.stop();
        }
    }

    @Test
    void send_postsCorrectJsonAndCompletesWithoutErrors() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-1"))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        assertThat(capturedRequests).hasSize(1);
        CapturedRequest request = capturedRequests.getFirst();
        assertThat(request.url()).isEqualTo(BASE_URL + SEND_PATH);
        assertThat(request.bearerToken()).isEmpty();
        var body = Json.parse(request.body(), JsonNode.class);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        JsonNode message = body.get(0);
        assertThat(message.get("to").asText()).isEqualTo(TOKEN_A);
        assertThat(message.get("title").asText()).isEqualTo("hello");
        assertThat(message.get("body").asText()).isEqualTo("world");
        assertThat(message.get("data").get("category").asText()).isEqualTo("PLUG_IN");
        assertThat(message.get("channelId").asText()).isEqualTo("default");

        verifyNoInteractions(listener);
    }

    @Test
    void send_emptyList_completesWithoutHttpCall() {
        startSender(Optional.empty());

        assertThat(sender.send(List.of())).succeedsWithin(NO_WAIT);

        assertThat(capturedRequests).isEmpty();
        verifyNoInteractions(listener);
    }

    @Test
    void send_oversized_throws() {
        startSender(Optional.empty());
        List<ExpoPushMessage> oversized = Collections.nCopies(MAX_MESSAGES_PER_SEND + 1, messageFor(TOKEN_A));

        assertThatThrownBy(() -> sender.send(oversized)).isInstanceOf(IllegalArgumentException.class);
        assertThat(capturedRequests).isEmpty();
        verifyNoInteractions(listener);
    }

    @Test
    void send_ticketDeviceNotRegistered_firesOnDeadToken() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data",
                                                                            List.of(errorTicket("not registered", "DeviceNotRegistered"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        verify(listener).onDeadToken(TOKEN_A);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_ticketOtherError_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data",
                                                                            List.of(errorTicket("rate limit", "MessageRateExceeded"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("MessageRateExceeded").contains("rate limit");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_unexpectedResponseShape_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of())));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Unexpected Expo send response shape");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_nonArrayData_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", "oops"))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Unexpected Expo send response shape");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_dataSizeMismatch_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of())));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Unexpected Expo send response shape");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_ticketOkWithoutId_doesNotScheduleReceiptPoll() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data",
                                                                            List.of(Map.of("status", "ok"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        assertThat(capturedRequests).hasSize(1);
        verifyNoInteractions(listener);
    }

    @Test
    void send_ticketUnknownStatus_ignoredSilently() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data",
                                                                            List.of(Map.of("status", "queued"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        assertThat(capturedRequests).hasSize(1);
        verifyNoInteractions(listener);
    }

    @Test
    void receipts_nonObjectData_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", List.of()))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Unexpected Expo receipts response shape");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void receipts_missingTicketId_ignoredSilently() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", Map.of()))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        assertThat(capturedRequests).hasSize(2);
        verifyNoInteractions(listener);
    }

    @Test
    void send_httpFailure_propagatesToFuture() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.failedFuture(new IOException("boom")));

        assertThat(sender.send(List.of(messageFor(TOKEN_A))))
                .failsWithin(NO_WAIT)
                .withThrowableThat()
                .withCauseInstanceOf(IOException.class);
        verifyNoInteractions(listener);
    }

    @Test
    void send_okTickets_polledAfterDelay_andDeadReceiptFiresOnDeadToken() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A", "rcpt-B"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", Map.of(
                "rcpt-A", Map.of("status", "ok"),
                "rcpt-B", errorTicket("gone", "DeviceNotRegistered"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A), messageFor(TOKEN_B)))).succeedsWithin(NO_WAIT);
        assertThat(capturedRequests).hasSize(1);

        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        assertThat(capturedRequests).hasSize(2);
        CapturedRequest receiptsRequest = capturedRequests.get(1);
        assertThat(receiptsRequest.url()).isEqualTo(BASE_URL + GET_RECEIPTS_PATH);
        assertThat(Json.parse(receiptsRequest.body(), JsonNode.class).get("ids")).hasSize(2);
        verify(listener).onDeadToken(TOKEN_B);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void send_okTickets_otherReceiptError_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", Map.of(
                "rcpt-A", errorTicket("server bug", "InternalServerError"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("InternalServerError");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void receipts_unexpectedResponseShape_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of())));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Unexpected Expo receipts response shape");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void receipts_httpFailure_firesOnUnexpectedError() {
        startSender(Optional.empty());
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.failedFuture(new IOException("boom")));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        clock.advanceTimeAndTick(Duration.ofMinutes(20));

        verify(listener).onUnexpectedError(errorCaptor.capture());
        assertThat(errorCaptor.getValue()).contains("Expo receipts poll failed");
        verifyNoMoreInteractions(listener);
    }

    @Test
    void accessToken_setsBearerToken_onSendAndPoll() {
        startSender(Optional.of("secret-token"));
        cannedResponses.add(CompletableFuture.completedFuture(okSendResponse(List.of("rcpt-A"))));
        cannedResponses.add(CompletableFuture.completedFuture(jsonOf(Map.of("data", Map.of(
                "rcpt-A", Map.of("status", "ok"))))));

        assertThat(sender.send(List.of(messageFor(TOKEN_A)))).succeedsWithin(NO_WAIT);
        assertThat(capturedRequests.get(0).bearerToken()).contains("secret-token");

        clock.advanceTimeAndTick(Duration.ofMinutes(20));
        assertThat(capturedRequests.get(1).bearerToken()).contains("secret-token");
        verifyNoInteractions(listener);
    }

    private void startSender(Optional<String> accessToken) {
        sender = new ExpoPushSenderImpl(clock, listener, accessToken, BASE_URL) {
            @Override
            protected CompletableFuture<JsonNode> postJson(String url, String jsonBody, Optional<String> bearerToken) {
                capturedRequests.add(new CapturedRequest(url, jsonBody, bearerToken));
                CompletableFuture<JsonNode> response = cannedResponses.poll();
                if (response == null) {
                    throw new AssertionError("no canned response for request to " + url);
                }
                return response;
            }
        };
        sender.start();
    }

    private static ExpoPushMessage messageFor(String token) {
        return ExpoPushMessage.builder()
                              .setToken(token)
                              .setTitle("hello")
                              .setBody("world")
                              .putData("category", "PLUG_IN")
                              .setChannelId("default")
                              .build();
    }

    private static JsonNode okSendResponse(List<String> receiptIds) {
        List<Map<String, String>> tickets = receiptIds.stream()
                                                      .map(id -> Map.of("status", "ok", "id", id))
                                                      .toList();
        return jsonOf(Map.of("data", tickets));
    }

    private static Map<String, Object> errorTicket(String message, String errorType) {
        return Map.of(
                "status", "error",
                "message", message,
                "details", Map.of("error", errorType));
    }

    private static JsonNode jsonOf(Object payload) {
        return Json.parse(Json.stringify(payload), JsonNode.class);
    }

    private record CapturedRequest(String url, String body, Optional<String> bearerToken) {}
}
