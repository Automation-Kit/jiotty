package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.UnserialisablePayload;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CREATED_201;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AsyncExchangeTest {
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    private static final Answer DEADLINE_ANSWER = Answer.of(SERVICE_UNAVAILABLE_503, new TextBody("too slow"));
    private final CapturingServletOutputStream stream = new CapturingServletOutputStream();
    private final List<Exception> encodingFailures = new ArrayList<>();
    private final List<Deadline.Stall> expiries = new ArrayList<>();
    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private final Map<String, Object> requestAttributesByName = new HashMap<>();
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;
    private AsyncExchange exchange;

    @BeforeEach
    void setUp() {
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(stream));
        // A request's attributes, which the exchange records itself in.
        lenient().doAnswer(invocation -> requestAttributesByName.put(invocation.getArgument(0), invocation.getArgument(1)))
                 .when(request).setAttribute(anyString(), any());
        lenient().doAnswer(invocation -> requestAttributesByName.remove(invocation.<String>getArgument(0))).when(request).removeAttribute(anyString());
        lenient().when(request.getAttribute(anyString())).thenAnswer(invocation -> requestAttributesByName.get(invocation.<String>getArgument(0)));
        startExchange(Deadline.none());
    }

    @AfterEach
    void tearDown() {
        assertThat(stream.leakedBuffers()).isEmpty();
    }

    @Test
    void startsTheAsyncCycleWithNoContainerTimeout() {
        assertThat(asyncContext.getTimeout()).isZero();
        assertThat(exchange.isClaimed()).isFalse();
    }

    @Test
    void appliesTheStatusHeadersAndBodyThenCompletes() {
        Answer answer = Answer.of(CREATED_201, new JsonBody(Json.createWriterFor(CreatedResource.class), new CreatedResource("id-1")))
                              .withHeader("Location", "/things/id-1");

        exchange.answer(answer, encodingFailures::add);

        verify(response).setStatus(CREATED_201);
        verify(response).setHeader("Location", "/things/id-1");
        verify(response).setContentType(CONTENT_TYPE_JSON);
        assertThat(stream.output()).isEqualTo("{\"id\":\"id-1\"}");
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(encodingFailures).isEmpty();
    }

    @Test
    void answersAStatusAloneWithNoBody() {
        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);

        verify(response).setStatus(NO_CONTENT_204);
        assertThat(stream.output()).isEmpty();
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    @Test
    void reportsABodyThatCannotBeEncodedAndAnswersABare500() {
        Answer answer = Answer.of(CREATED_201, UnserialisablePayload.createJsonBody());

        exchange.answer(answer, encodingFailures::add);

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(encodingFailures).hasSize(1);
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    @Test
    void reportsABodyWhoseEncoderThrowsAnUncheckedException() {
        var failure = new IllegalStateException("defective body");

        exchange.answer(Answer.of(CREATED_201, new ThrowingBody(failure)), encodingFailures::add);

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(encodingFailures).containsExactly(failure);
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    /// The exchange can end while the answer waits for a container thread, as when the client goes away; the answer is then discarded, and the failure the
    /// ended exchange explains raises no alert.
    @Test
    void anExchangeThatEndsBeforeItsAnswerStartsIsLeftAlone() {
        asyncContext.abort();

        assertThatCode(() -> exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add)).doesNotThrowAnyException();
        assertThat(stream.output()).isEmpty();
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A defect that stops the response opening while the exchange is open, such as a response no Jetty container served, raises an alert, and the exchange
    /// is completed.
    @Test
    void alertsOnAResponseThatCannotBeOpenedWhileTheExchangeIsOpen() {
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenThrow(new IllegalStateException("not served by Jetty")));

        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);

        assertUnexpectedFailureAlerted();
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    @Test
    void anAnswerToAnExchangeAlreadyCompletedIsDropped() {
        asyncContext.complete();

        assertThatCode(() -> exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add)).doesNotThrowAnyException();
        assertThat(stream.output()).isEmpty();
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    @Test
    void completesAnExchangeWhoseResponseIsWrittenAlready() {
        AsyncExchange.Claim claim = claim();

        claim.complete();

        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThatCode(claim::complete).doesNotThrowAnyException();
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    @Test
    void completesWithoutAnsweringWhenTheResponseCannotBeOpened() {
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenThrow(new IOException("gone")));

        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);

        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A completion that fails after the container has finished with the exchange is expected, so it raises no alert.
    @Test
    void aCompletionThatFailsOnceTheContainerHasFinishedIsQuiet() {
        AsyncExchange.Claim claim = claim();
        asyncContext.complete();
        asyncContext.refuseCompletion();

        assertThatCode(claim::completeNow).doesNotThrowAnyException();

        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A completion that fails while the container has yet to finish with the exchange is explained by nothing in its state, so it is alerted.
    @Test
    void alertsOnACompletionThatFailsWhileTheExchangeIsOpen() {
        asyncContext.failCompletionWith(new IllegalStateException("defective container"));

        assertThatCode(() -> claim().complete()).doesNotThrowAnyException();

        assertUnexpectedFailureAlerted();
    }

    @Test
    void runsATaskOnAContainerThread() {
        List<String> ranTasks = new ArrayList<>();

        assertThat(exchange.runOnContainerThread(() -> ranTasks.add("task"))).isTrue();

        assertThat(ranTasks).containsExactly("task");
    }

    /// A container whose thread pool has stopped rejects every task, as when the server shuts down: routine, so it raises no alert.
    @Test
    void aTaskTheStoppedContainerRejectsIsQuiet() {
        asyncContext.failStartsWith(new RejectedExecutionException("stopped"));

        assertThat(exchange.runOnContainerThread(() -> {})).isFalse();

        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A container that has finished with the exchange explains a failed start, so it raises no alert.
    @Test
    void aTaskThatFailsToStartOnceTheContainerHasFinishedIsQuiet() {
        asyncContext.complete();
        asyncContext.failStartsWith(new NullPointerException("recycled"));

        assertThat(exchange.runOnContainerThread(() -> {})).isFalse();

        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A start that fails while the container has yet to finish with the exchange is explained by nothing in its state, so it is alerted.
    @Test
    void alertsOnATaskThatFailsToStartWhileTheExchangeIsOpen() {
        asyncContext.failStartsWith(new NullPointerException("defect"));

        assertThat(exchange.runOnContainerThread(() -> {})).isFalse();

        assertUnexpectedFailureAlerted();
    }

    @Test
    void onlyTheFirstClaimWins() {
        assertThat(exchange.claim()).isPresent();

        assertThat(exchange.claim()).isEmpty();
        assertThat(exchange.isClaimed()).isTrue();
    }

    /// The first answer holds the claim, so a second, such as a late action's after its deadline, writes nothing.
    @Test
    void aSecondAnswerIsDropped() {
        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);

        exchange.answer(Answer.of(CREATED_201, new TextBody("late")), encodingFailures::add);

        verify(response, never()).setStatus(CREATED_201);
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    /// A dispatch returns the request to the container, so a throw in the next dispatch belongs to the exchange that dispatch starts.
    @Test
    void aDispatchReturnsTheRequestToTheContainer() {
        claim().dispatch();

        assertThat(asyncContext.dispatches()).isEqualTo(1);
        assertThat(AsyncExchange.failDispatch(request, new IllegalStateException("next dispatch"), DEADLINE_ANSWER)).isFalse();
    }

    @Test
    void runsAnEndCallbackOnceTheContainerHasFinishedWithTheExchange() {
        asyncContext.holdContainerCompletion();
        List<String> endCallbacks = new ArrayList<>();
        exchange.whenEnded(() -> endCallbacks.add("ended"));
        asyncContext.complete();
        assertThat(endCallbacks).isEmpty();

        asyncContext.releaseContainerCompletion();

        assertThat(endCallbacks).containsExactly("ended");
    }

    @Test
    void runsAnEndCallbackOnAnAbort() {
        List<String> endCallbacks = new ArrayList<>();
        exchange.whenEnded(() -> endCallbacks.add("ended"));

        asyncContext.abort();

        assertThat(endCallbacks).containsExactly("ended");
    }

    @Test
    void runsAnEndCallbackAtOnceWhenTheExchangeHasEndedAlready() {
        asyncContext.abort();
        List<String> endCallbacks = new ArrayList<>();

        exchange.whenEnded(() -> endCallbacks.add("ended"));

        assertThat(endCallbacks).containsExactly("ended");
    }

    @Test
    void answersWithTheDeadlinesAnswerOnceItExpires() {
        startExchange(createDeadline());
        assertThat(asyncContext.pendingDeadlines()).containsExactly(DEADLINE);

        asyncContext.expireDeadlines();

        assertThat(expiries).containsExactly(Deadline.Stall.HANDLING);
        verify(response).setStatus(SERVICE_UNAVAILABLE_503);
        assertThat(stream.output()).isEqualTo("too slow");
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    @Test
    void reportsADeadlineExpiringWhileTheBodyArrivesAsReceivingBody() {
        startExchange(createDeadline());
        exchange.bodyReceiving();

        asyncContext.expireDeadlines();

        assertThat(expiries).containsExactly(Deadline.Stall.RECEIVING_BODY);
    }

    @Test
    void reportsADeadlineExpiringAfterTheBodyArrivedAsHandling() {
        startExchange(createDeadline());
        exchange.bodyReceiving();
        exchange.bodyReceived();

        asyncContext.expireDeadlines();

        assertThat(expiries).containsExactly(Deadline.Stall.HANDLING);
    }

    /// An answer before the deadline holds the claim, and the exchange's end cancels the deadline, so the timer holds nothing past it.
    @Test
    void anAnswerBeforeTheDeadlineCancelsIt() {
        startExchange(createDeadline());

        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);

        assertThat(asyncContext.pendingDeadlines()).isEmpty();
        assertThat(expiries).isEmpty();
    }

    @Test
    void aDeadlineExpiringAfterAClaimDoesNothing() {
        startExchange(createDeadline());
        claim();

        asyncContext.expireDeadlines();

        assertThat(expiries).isEmpty();
        verify(response, never()).setStatus(SERVICE_UNAVAILABLE_503);
    }

    @Test
    void anAnswerAfterTheDeadlineIsDropped() {
        startExchange(createDeadline());
        asyncContext.expireDeadlines();

        exchange.answer(Answer.of(CREATED_201), encodingFailures::add);

        verify(response, never()).setStatus(CREATED_201);
    }

    /// A container whose timer has stopped, as while the server stops, rejects the deadline; the exchange goes on without one.
    @Test
    void goesOnWithoutADeadlineTheStoppedTimerRejects() {
        asyncContext = new FakeAsyncContext(request, response);
        asyncContext.failSchedulingWith(new RejectedExecutionException("stopped"));
        when(request.startAsync()).thenReturn(asyncContext);

        exchange = AsyncExchange.start(alertService, request, response, createDeadline());

        assertThat(asyncContext.pendingDeadlines()).isEmpty();
        exchange.answer(Answer.of(NO_CONTENT_204), encodingFailures::add);
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    /// The container sends no response for a request that has gone async, so a dispatch that throws is answered here, and alerted.
    @Test
    void answersADispatchThatThrewWithTheGivenAnswer() {
        assertThat(AsyncExchange.failDispatch(request, new IllegalStateException("defective handler"), DEADLINE_ANSWER)).isTrue();

        verify(response).setStatus(SERVICE_UNAVAILABLE_503);
        assertThat(asyncContext.completions()).isEqualTo(1);
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(AsyncExchange.DISPATCH_FAILED_ALERT_TITLE));
    }

    @Test
    void endsTheOutputOfADispatchThatThrewAfterStartingOne() {
        List<EndReason> endReasons = new ArrayList<>();
        ContainerOutput output = getAsUnchecked(() -> claim().createOutput(new NeverFinishingSource(), endReasons::add));
        output.start();

        AsyncExchange.failDispatch(request, new IllegalStateException("defective handler"), DEADLINE_ANSWER);

        assertThat(endReasons).containsExactly(EndReason.Closed.INSTANCE);
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    /// A holder that has claimed without starting an output owns the answer, so the failure raises an alert and the exchange is left to that holder.
    @Test
    void leavesAnExchangeClaimedWithoutAnOutputToItsHolder() {
        claim();

        AsyncExchange.failDispatch(request, new IllegalStateException("defective handler"), DEADLINE_ANSWER);

        assertThat(asyncContext.isCompleted()).isFalse();
        assertThat(alertService.activeAlertsById()).hasSize(1);
    }

    @Test
    void leavesADispatchThatStartedNoExchangeToTheContainer() {
        requestAttributesByName.clear();

        assertThat(AsyncExchange.failDispatch(request, new IllegalStateException("defective handler"), DEADLINE_ANSWER)).isFalse();
    }

    @Test
    void anAbortEndsTheExchangeWithoutCompletingIt() {
        var cause = new IOException("the archive failed");

        claim().abort(cause);

        assertThat(asyncContext.abortCause()).containsSame(cause);
        assertThat(asyncContext.isCompleted()).isFalse();
        assertThat(exchange.isOpen()).isFalse();
    }

    @Test
    void alertsOnAnAbortThatFailsWhileTheExchangeIsOpen() {
        asyncContext.failAbortsWith(new IllegalStateException("defective container"));

        claim().abort(new IOException("the archive failed"));

        assertUnexpectedFailureAlerted();
    }

    /// Once the exchange has ended, the container may be serving the connection's next request, so an abort is ignored.
    @Test
    void anAbortOnceTheExchangeHasEndedIsIgnored() {
        AsyncExchange.Claim claim = claim();
        claim.completeNow();

        claim.abort(new IOException("the archive failed"));

        assertThat(asyncContext.abortCause()).isEmpty();
    }

    /// A one-shot source has its one body ready until it is taken, and is finished from then on, as [OutputSource] requires.
    @Test
    void aSingleBodySourceHasItsBodyReadyUntilItIsTaken() {
        var body = new TextBody("only");
        var source = new AsyncExchange.SingleBodySource(body);
        assertThat(source.hasReady()).isTrue();
        assertThat(source.isFinished()).isFalse();

        assertThat(source.takeReady()).containsExactly(body);

        assertThat(source.hasReady()).isFalse();
        assertThat(source.isFinished()).isTrue();
        assertThat(source.takeReady()).isEmpty();
    }

    private void startExchange(Deadline deadline) {
        asyncContext = new FakeAsyncContext(request, response);
        lenient().when(request.startAsync()).thenReturn(asyncContext);
        exchange = AsyncExchange.start(alertService, request, response, deadline);
    }

    private Deadline createDeadline() {
        return Deadline.after(DEADLINE, DEADLINE_ANSWER, encodingFailures::add, expiries::add);
    }

    private AsyncExchange.Claim claim() {
        return exchange.claim().orElseThrow();
    }

    /// The alert names the exchange, so its occurrence can be matched to that exchange's log lines.
    private void assertUnexpectedFailureAlerted() {
        assertThat(alertService.activeAlertsById().entrySet())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getValue().title()).isEqualTo(AsyncExchange.UNEXPECTED_FAILURE_ALERT_TITLE);
                    assertThat(alertService.eventsByAlertId(entry.getKey()))
                            .singleElement()
                            .satisfies(event -> assertThat(event.description()).startsWith('[' + exchange.id() + ']'));
                });
    }

    private record CreatedResource(String id) {}

    /// A [ResponseBody] whose encoding throws the given unchecked exception.
    private record ThrowingBody(RuntimeException failure) implements ResponseBody {
        @Override
        public void applyHeadersTo(HttpServletResponse response) {
        }

        @Override
        public void writeTo(OutputStream output) {
            throw failure;
        }
    }

    /// An [OutputSource] with nothing to write, ever.
    private static final class NeverFinishingSource implements OutputSource {
        @Override
        public List<ResponseBody> takeReady() {
            return List.of();
        }

        @Override
        public boolean hasReady() {
            return false;
        }

        @Override
        public boolean isFinished() {
            return false;
        }
    }
}
