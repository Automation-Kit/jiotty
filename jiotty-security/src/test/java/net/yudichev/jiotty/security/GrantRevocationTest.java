package net.yudichev.jiotty.security;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.testutil.LogCapture;
import net.yudichev.jiotty.common.testutil.LogCapture.CapturedLine;
import net.yudichev.jiotty.security.StoredGrantRevoker.Result;
import okhttp3.FormBody;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubCalls;
import static net.yudichev.jiotty.security.GrantRevocation.INVALID_TOKEN_ERROR;
import static net.yudichev.jiotty.security.GrantRevocation.TOKEN_PARAM;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.CREDENTIAL_DEAD_ERRORS;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.TOKEN_RETRY_INITIAL_INTERVAL;
import static net.yudichev.jiotty.security.StoredGrantRevokerImpl.RETRY_MAX_ELAPSED_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(MockitoExtension.class)
class GrantRevocationTest {
    private static final String API_NAME = "testApi";
    private static final String REVOCATION_URL = "http://token-host/revoke";
    private static final String TOKEN_URL = "http://token-host/token";
    /// Free of punctuation, as are the tokens below, so a parser quoting one quotes it whole.
    private static final String REFRESH_TOKEN_VALUE = "storedRefreshToken";
    private static final String ROTATED_REFRESH_TOKEN = "rotatedRefreshToken";

    private final ProgrammableClock clock = new ProgrammableClock();
    private final List<Request> requestLog = new ArrayList<>();
    /// The answers to successive requests; once one is left it answers every further request.
    private final Queue<Function<Request, Response>> answers = new ArrayDeque<>();
    @Mock
    private OkHttpClient httpClient;
    private SchedulingExecutor executor;

    @BeforeEach
    void setUp() {
        clock.setTimeAndTick(Instant.parse("2025-01-01T00:00:00Z"));
        executor = clock.createSingleThreadedSchedulingExecutor(API_NAME);
        stubCalls(httpClient, request -> {
            requestLog.add(request);
            return (answers.size() > 1 ? answers.remove() : checkNotNull(answers.peek())).apply(request);
        });
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void rfc7009_postsTheRefreshTokenAndEndsOnSuccess() {
        answer(OK_200, "");

        CompletableFuture<Result> revocation = revoke(rfc7009());
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).singleElement().satisfies(request -> {
            assertThat(request.url()).hasToString(REVOCATION_URL);
            var body = (FormBody) checkNotNull(request.body());
            assertThat(body.name(0)).isEqualTo(TOKEN_PARAM);
            assertThat(body.value(0)).isEqualTo(REFRESH_TOKEN_VALUE);
        });
    }

    /// A client error naming a grant that is already gone leaves nothing to end, whichever 4xx status carries it.
    @ParameterizedTest
    @MethodSource
    void aGrantAlreadyGone_ends(int status, String error) {
        answer(status, """
                       {"error": "%s"}""".formatted(error));

        CompletableFuture<Result> revocation = revoke(rfc7009());
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(1);
    }

    static Stream<Arguments> aGrantAlreadyGone_ends() {
        return Stream.concat(Stream.of(arguments(BAD_REQUEST_400, INVALID_TOKEN_ERROR), arguments(UNAUTHORIZED_401, INVALID_TOKEN_ERROR)),
                             CREDENTIAL_DEAD_ERRORS.stream().map(error -> arguments(BAD_REQUEST_400, error)));
    }

    @ParameterizedTest
    @MethodSource
    void anyOtherClientError_isRefusedWithoutRetrying(int status, String body) {
        answer(status, body);

        CompletableFuture<Result> revocation = revoke(rfc7009());
        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.REFUSED);
        assertThat(requestLog).hasSize(1);
    }

    static Stream<Arguments> anyOtherClientError_isRefusedWithoutRetrying() {
        return Stream.of(arguments(BAD_REQUEST_400, """
                                                    {"error": "invalid_client"}"""),
                         arguments(FORBIDDEN_403, """
                                                  {"error": "invalid_grant_type"}"""),
                         arguments(BAD_REQUEST_400, "not json"));
    }

    @ParameterizedTest
    @MethodSource
    void aServerSideOrRateLimitFailure_isRetried(int status) {
        answer(status, "");
        answer(OK_200, "");

        CompletableFuture<Result> revocation = revoke(rfc7009());
        clock.tick();
        assertThat(revocation).isNotDone();

        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(2);
    }

    static Stream<Integer> aServerSideOrRateLimitFailure_isRetried() {
        return Stream.of(INTERNAL_SERVER_ERROR_500, SERVICE_UNAVAILABLE_503, TOO_MANY_REQUESTS_429);
    }

    @Test
    void retryBudgetSpent_isUnreachable() {
        answer(SERVICE_UNAVAILABLE_503, "");

        CompletableFuture<Result> revocation = revoke(rfc7009());
        // Doubling the budget leaves room for the randomised, growing retry intervals to cross it.
        clock.advanceTimeAndTick(RETRY_MAX_ELAPSED_TIME.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.UNREACHABLE);
        assertThat(requestLog.size()).isGreaterThan(1);
    }

    @Test
    void rotation_sendsTheSecondGrantWithTheRefreshTokenTheFirstBroughtBack() {
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answerWithToken("last-rt");

        CompletableFuture<Result> revocation = revoke(rotation());
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).extracting(GrantRevocationTest::refreshTokenSent).containsExactly(REFRESH_TOKEN_VALUE, ROTATED_REFRESH_TOKEN);
    }

    @Test
    void rotationAnsweredWithAnUnreadableBody_isRefused() {
        answer(OK_200, "not json");

        CompletableFuture<Result> revocation = revoke(rotation());
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.REFUSED);
        assertThat(requestLog).hasSize(1);
    }

    /// A rotation that brings a token back and then fails until the budget is spent logs at every level it has, and none of it may name a token.
    @Test
    void aRotationThatGivesUp_logsNoToken() {
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answer(SERVICE_UNAVAILABLE_503, "");

        try (LogCapture log = LogCapture.capture(GrantRevocation.class, Level.DEBUG)) {
            CompletableFuture<Result> revocation = revoke(rotation());
            clock.advanceTimeAndTick(RETRY_MAX_ELAPSED_TIME.multipliedBy(2));

            assertThat(revocation).isCompletedWithValue(Result.UNREACHABLE);
            assertNoTokenIn(log);
        }
    }

    /// An authorisation server may quote the token it refuses, in an OAuth error or in a body that is no OAuth error at all, so only the error code is logged.
    @ParameterizedTest
    @MethodSource
    void aRefusalQuotingTheToken_logsNoToken(String body) {
        answer(BAD_REQUEST_400, body);

        try (LogCapture log = LogCapture.capture(GrantRevocation.class, Level.DEBUG)) {
            CompletableFuture<Result> revocation = revoke(rfc7009());
            clock.tick();

            assertThat(revocation).isCompletedWithValue(Result.REFUSED);
            assertNoTokenIn(log);
        }
    }

    static Stream<String> aRefusalQuotingTheToken_logsNoToken() {
        return Stream.of("""
                         {"error": "invalid_client", "error_description": "token %s rejected"}""".formatted(REFRESH_TOKEN_VALUE),
                         REFRESH_TOKEN_VALUE + " rejected");
    }

    @Test
    void anAnswerTheOwnerDrops_neverEndsTheRevocation() {
        answer(OK_200, "");

        CompletableFuture<Result> revocation = revoke(rfc7009(), _ -> {});
        clock.tick();

        assertThat(revocation).isNotDone();
    }

    private CompletableFuture<Result> revoke(GrantRevocation.Method method) {
        return revoke(method, executor::execute);
    }

    private CompletableFuture<Result> revoke(GrantRevocation.Method method, Consumer<Runnable> outcomeDispatcher) {
        return new GrantRevocation(API_NAME,
                                   method,
                                   httpClient,
                                   executor,
                                   outcomeDispatcher,
                                   () -> OAuth2TokenManagerImpl.retryBackOff(clock, RETRY_MAX_ELAPSED_TIME))
                .revoke(REFRESH_TOKEN_VALUE);
    }

    private void answer(int status, String body) {
        answers.add(request -> response(request, status, body));
    }

    private void answerWithToken(String refreshToken) {
        answer(OK_200, """
                       {"access_token": "at", "refresh_token": "%s", "expires_in": 3600, "token_type": "Bearer"}""".formatted(refreshToken));
    }

    private static GrantRevocation.Method rfc7009() {
        return new GrantRevocation.Method.Rfc7009(REVOCATION_URL);
    }

    private static GrantRevocation.Method rotation() {
        return new GrantRevocation.Method.Rotation(TOKEN_URL, "client-id", Optional.empty());
    }

    private static void assertNoTokenIn(LogCapture log) {
        assertThat(log.lines()).isNotEmpty()
                               .extracting(CapturedLine::message)
                               .noneMatch(message -> message.contains(REFRESH_TOKEN_VALUE) || message.contains(ROTATED_REFRESH_TOKEN));
    }

    private static String refreshTokenSent(Request request) {
        var body = (FormBody) checkNotNull(request.body());
        for (int i = 0; i < body.size(); i++) {
            if (OAuth2TokenManagerImpl.REFRESH_TOKEN.equals(body.name(i))) {
                return body.value(i);
            }
        }
        throw new AssertionError("no refresh token in " + request);
    }
}
