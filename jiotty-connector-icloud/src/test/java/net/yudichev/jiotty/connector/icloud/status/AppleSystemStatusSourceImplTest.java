package net.yudichev.jiotty.connector.icloud.status;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSource.IcloudCalendarStatus;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.OkHttpClientAssertions.assertGivesUpAfter;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSourceImpl.APPLE_ACCOUNT_SERVICE_NAME;
import static net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSourceImpl.CALENDAR_SERVICE_NAME;
import static net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSourceImpl.SIGN_IN_SERVICE_NAME;
import static net.yudichev.jiotty.connector.icloud.status.AppleSystemStatusSourceImpl.STATUS_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(MockitoExtension.class)
class AppleSystemStatusSourceImplTest {
    /// The start of the iCloud Calendar event in the captured file.
    private static final Instant EVENT_START = Instant.parse("2026-11-10T22:49:00Z");
    private static final Instant DURING_EVENT = EVENT_START.plus(Duration.ofMinutes(30));
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /// How many attempts [RepeatingRetryExecutor] makes at an operation that keeps failing.
    private static final int ATTEMPT_LIMIT = 4;
    /// A service whose events have no bearing on reading an iCloud calendar.
    private static final String UNRELATED_SERVICE_NAME = "iCloud Notes";

    private final ProgrammableClock clock = new ProgrammableClock();
    private final RetryableOperationExecutor retryExecutor = new RepeatingRetryExecutor();
    @Mock
    private OkHttpClient httpClient;
    private List<Request> requests = List.of();
    private final AppleSystemStatusSourceImpl source = newSource(clock);

    @BeforeEach
    void setUp() {
        clock.setTime(DURING_EVENT);
    }

    @AfterEach
    void tearDown() {
        source.stop();
    }

    @Test
    void theFileCapturedDuringAnIcloudCalendarEvent_readsAsAnOngoingEvent() {
        answerWith(request -> response(request, OK_200, readCapturedFile()));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.ONGOING_EVENT);
        assertThat(requests).singleElement().returns(STATUS_REQUEST.url(), Request::url);
    }

    /// The request is the same for every user: a GET of the fixed URL with no query, no header of its own and no body.
    @Test
    void theRequestCarriesNothingAboutAUser() {
        answerWith(request -> response(request, OK_200, readCapturedFile()));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.url()).isEqualTo(STATUS_REQUEST.url());
            assertThat(request.url().query()).isNull();
            assertThat(request.headers().size()).isZero();
            assertThat(request.body()).isNull();
        });
    }

    /// The captured file also lists an ongoing event of Find My, a service on which reading an iCloud calendar does not depend.
    @Test
    void theCapturedFileBeforeItsIcloudCalendarEventStarted_readsAsNoOngoingEvent() {
        var clockBeforeTheEvent = new ProgrammableClock();
        clockBeforeTheEvent.setTime(EVENT_START.minusSeconds(1));
        AppleSystemStatusSourceImpl sourceBeforeTheEvent = newSource(clockBeforeTheEvent);
        answerWith(request -> response(request, OK_200, readCapturedFile()));
        sourceBeforeTheEvent.start();

        assertThat(sourceBeforeTheEvent.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.NO_ONGOING_EVENT);
    }

    @ParameterizedTest
    @MethodSource
    void anEvent_isOngoingFromItsStartUntilItsEnd(String serviceName, String start, String end, IcloudCalendarStatus expectedStatus) {
        answerWith(request -> response(request, OK_200, """
                                                        {"services": [{"serviceName": "%s", "events": [{"epochStartDate": %s, "epochEndDate": %s}]}]}
                                                        """.formatted(serviceName, start, end)));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(expectedStatus);
    }

    static Stream<Arguments> anEvent_isOngoingFromItsStartUntilItsEnd() {
        String nowEpochMillis = Long.toString(DURING_EVENT.toEpochMilli());
        String earlierEpochMillis = Long.toString(EVENT_START.toEpochMilli());
        String laterEpochMillis = Long.toString(EVENT_START.plus(Duration.ofHours(1)).toEpochMilli());
        // each of the three services counts
        return Stream.of(arguments(CALENDAR_SERVICE_NAME, earlierEpochMillis, "null", IcloudCalendarStatus.ONGOING_EVENT),
                         arguments(SIGN_IN_SERVICE_NAME, earlierEpochMillis, "null", IcloudCalendarStatus.ONGOING_EVENT),
                         arguments(APPLE_ACCOUNT_SERVICE_NAME, earlierEpochMillis, "null", IcloudCalendarStatus.ONGOING_EVENT),
                         // the start is inclusive and the end exclusive
                         arguments(CALENDAR_SERVICE_NAME, nowEpochMillis, laterEpochMillis, IcloudCalendarStatus.ONGOING_EVENT),
                         arguments(CALENDAR_SERVICE_NAME, earlierEpochMillis, laterEpochMillis, IcloudCalendarStatus.ONGOING_EVENT),
                         arguments(CALENDAR_SERVICE_NAME, earlierEpochMillis, nowEpochMillis, IcloudCalendarStatus.NO_ONGOING_EVENT),
                         // an event yet to start, such as planned maintenance
                         arguments(CALENDAR_SERVICE_NAME, laterEpochMillis, "null", IcloudCalendarStatus.NO_ONGOING_EVENT),
                         // an event with no start
                         arguments(CALENDAR_SERVICE_NAME, "null", "null", IcloudCalendarStatus.NO_ONGOING_EVENT));
    }

    @ParameterizedTest
    @MethodSource
    void aFileWithNoOngoingEventOfTheServices_readsAsNoOngoingEvent(String body) {
        answerWith(request -> response(request, OK_200, body));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.NO_ONGOING_EVENT);
    }

    static Stream<Arguments> aFileWithNoOngoingEventOfTheServices_readsAsNoOngoingEvent() {
        return Stream.of(// an ongoing event of an unrelated service
                arguments("""
                          {"services": [{"serviceName": "%s", "events": [{"epochStartDate": %s, "epochEndDate": null}]},
                                        {"serviceName": "%s", "events": []}]}
                          """.formatted(UNRELATED_SERVICE_NAME, EVENT_START.toEpochMilli(), CALENDAR_SERVICE_NAME)),
                // a listed service without events
                arguments("""
                          {"services": [{"serviceName": "%s"}, {"events": []}]}
                          """.formatted(CALENDAR_SERVICE_NAME)));
    }

    /// A response whose body reads as [IcloudCalendarStatus#UNREADABLE] completes the read after one request.
    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "not json",
            "{\"services\": 42}",
            "{\"services\": []}",
            "{\"services\": [{\"serviceName\": \"" + UNRELATED_SERVICE_NAME + "\", \"events\": []}]}"
    })
    void aFileOfAnotherShapeOrWithNoneOfTheServices_readsAsUnreadable(String body) {
        answerWith(request -> response(request, OK_200, body));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.UNREADABLE);
        assertThat(requests).hasSize(1);
    }

    @Test
    void anUnsuccessfulResponseToEveryAttempt_readsAsUnreadable() {
        answerWith(request -> response(request, SERVICE_UNAVAILABLE_503, "unavailable"));
        source.start();

        assertThat(source.readIcloudCalendarStatus()).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.UNREADABLE);
        assertThat(requests).hasSize(ATTEMPT_LIMIT);
    }

    @Test
    void aTransportFailureOfEveryAttempt_readsAsUnreadable() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        source.start();
        CompletableFuture<IcloudCalendarStatus> status = source.readIcloudCalendarStatus();

        for (int attempt = 1; attempt <= ATTEMPT_LIMIT; attempt++) {
            assertThat(status).isNotDone();
            takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
        }

        assertThat(status).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.UNREADABLE);
    }

    @Test
    void aFailedExchange_isRepeatedUntilAResponseIsRead() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        source.start();
        CompletableFuture<IcloudCalendarStatus> status = source.readIcloudCalendarStatus();

        takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
        takeOnlyPendingCall(pendingCalls).respond(SERVICE_UNAVAILABLE_503, "unavailable");
        takeOnlyPendingCall(pendingCalls).respond(OK_200, readCapturedFile());

        assertThat(status).succeedsWithin(Duration.ZERO).isEqualTo(IcloudCalendarStatus.ONGOING_EVENT);
        assertThat(pendingCalls).isEmpty();
    }

    /// Two attempts that fail with the same message are logged as one line, and the attempt that then fails with another message as a second.
    @Test
    void failedAttempts_areLoggedOnceForEachRunWithTheSameFailure() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        source.start();

        List<String> messages;
        try (var log = LogCapture.capture(AppleSystemStatusSourceImpl.class, Level.INFO)) {
            CompletableFuture<IcloudCalendarStatus> status = source.readIcloudCalendarStatus();
            takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
            takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));
            takeOnlyPendingCall(pendingCalls).fail(new IOException("timed out"));
            takeOnlyPendingCall(pendingCalls).respond(OK_200, readCapturedFile());
            assertThat(status).succeedsWithin(Duration.ZERO);
            messages = log.lines().stream().map(LogCapture.CapturedLine::message).toList();
        }

        assertThat(messages).satisfiesExactly(message -> assertThat(message).contains("retrying", "connection reset"),
                                              message -> assertThat(message).contains("retrying", "timed out"));
    }

    @Test
    void aReadAfterTheSourceStopped_neverCompletesAndSendsNothing() {
        answerWith(request -> response(request, OK_200, readCapturedFile()));
        source.start();
        source.stop();

        assertThat(source.readIcloudCalendarStatus()).isNotDone();
        assertThat(requests).isEmpty();
    }

    /// Stopping fails the request in flight, and that failure starts no further attempt.
    @Test
    void aReadInFlightWhenTheSourceStops_neverCompletesAndSendsNothingMore() {
        List<PendingCall> pendingCalls = stubDeferredCalls(httpClient);
        source.start();
        CompletableFuture<IcloudCalendarStatus> status = source.readIcloudCalendarStatus();
        PendingCall callInFlight = takeOnlyPendingCall(pendingCalls);

        source.stop();
        callInFlight.fail(new IOException("Canceled"));

        assertThat(status).isNotDone();
        assertThat(pendingCalls).isEmpty();
    }

    @Test
    void theClientGivesUpAfterTheTimeout() {
        assertGivesUpAfter(new AppleSystemStatusSourceImpl(clock, TIMEOUT, retryExecutor).createHttpClient(), TIMEOUT);
    }

    @Test
    void theClientLimitsTheSizeOfAResponseBody() {
        OkHttpClient client = new AppleSystemStatusSourceImpl(clock, TIMEOUT, retryExecutor).createHttpClient();

        assertThat(client.interceptors()).hasAtLeastOneElementOfType(BodySizeLimitInterceptor.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aTimeoutThatIsNotPositive_isRejected(long timeoutSeconds) {
        assertThatThrownBy(() -> new AppleSystemStatusSourceImpl(clock, Duration.ofSeconds(timeoutSeconds), retryExecutor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AppleSystemStatusSourceImpl newSource(ProgrammableClock sourceClock) {
        return new AppleSystemStatusSourceImpl(sourceClock, TIMEOUT, retryExecutor) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
    }

    private void answerWith(Function<Request, Response> responder) {
        requests = stubCalls(httpClient, responder);
    }

    private String readCapturedFile() {
        String resourceName = "system-status-2026-11-10.json";
        return new String(getAsUnchecked(() -> checkNotNull(getClass().getResourceAsStream(resourceName), resourceName).readAllBytes()), UTF_8);
    }

    /// Repeats a failed operation at once, reporting a backoff event before each repeat, and fails it once [#ATTEMPT_LIMIT] attempts have failed.
    private static final class RepeatingRetryExecutor implements RetryableOperationExecutor {
        @Override
        public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                            Supplier<? extends CompletableFuture<T>> action,
                                                            BiConsumer<Long, Throwable> backoffEventConsumer) {
            return attempt(action, backoffEventConsumer, 1);
        }

        private static <T> CompletableFuture<T> attempt(Supplier<? extends CompletableFuture<T>> action,
                                                        BiConsumer<Long, Throwable> backoffEventConsumer,
                                                        int attemptNumber) {
            return action.get().exceptionallyCompose(failure -> {
                if (attemptNumber == ATTEMPT_LIMIT) {
                    return failedFuture(new IllegalStateException("retried for too long", failure));
                }
                backoffEventConsumer.accept(0L, failure);
                return attempt(action, backoffEventConsumer, attemptNumber + 1);
            });
        }
    }
}
