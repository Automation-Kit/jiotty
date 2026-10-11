package net.yudichev.jiotty.security;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.security.StoredGrantRevoker.Result;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static net.yudichev.jiotty.security.GrantRevocation.ROTATIONS;
import static net.yudichev.jiotty.security.GrantRevocation.TOKEN_PARAM;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.REFRESH_TOKEN;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.TOKEN_RETRY_INITIAL_INTERVAL;
import static net.yudichev.jiotty.security.StoredGrantRevokerImpl.RETRY_MAX_ELAPSED_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StoredGrantRevokerImplTest {
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String API_NAME = "testApi";
    private static final String SCOPE = "test-scope";
    private static final String TOKEN_URL = "http://token-host/token";
    private static final String REVOCATION_URL = "http://token-host/revoke";
    private static final String VAR_STORE_KEY = OAuth2TokenManagerImpl.varStoreKey(API_NAME, CLIENT_ID, SCOPE);
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final String STORED_REFRESH_TOKEN = "stored-rt";
    private static final String ROTATED_REFRESH_TOKEN = "rotated-rt";
    private static final String LAST_REFRESH_TOKEN = "last-rt";

    private final ProgrammableClock clock = new ProgrammableClock();
    private final InMemoryVarStore varStore = new InMemoryVarStore();
    private final List<Request> requestLog = new ArrayList<>();
    /// The answers to successive requests; once one is left it answers every further request. A `null` answer leaves its request pending.
    private final Queue<Function<Request, @Nullable Response>> answers = new ArrayDeque<>();
    private @Nullable Callback pendingCallback;
    @Mock
    private OkHttpClient httpClient;
    @Mock
    private Call call;
    /// The revoker the test started, stopped after it.
    private @Nullable StoredGrantRevokerImpl startedRevoker;

    @BeforeEach
    void setUp() {
        clock.setTimeAndTick(NOW);
    }

    @AfterEach
    void tearDown() {
        if (startedRevoker != null) {
            startedRevoker.stop();
        }
    }

    @Test
    void noStoredToken_endsWithoutARequest() {
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.NOTHING_STORED);
        assertThat(requestLog).isEmpty();
    }

    /// The second refresh spends the token the first brought back, which ends the stored token's grace period at once.
    @Test
    void rotation_refreshesTwiceWithTheTokenTheFirstBroughtBack_thenDeletesTheStoredToken() {
        storeToken(STORED_REFRESH_TOKEN);
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answerWithToken(LAST_REFRESH_TOKEN);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(ROTATIONS)
                              .allSatisfy(request -> assertThat(request.url()).hasToString(TOKEN_URL))
                              .extracting(StoredGrantRevokerImplTest::formParams)
                              .containsExactly(refreshGrant(STORED_REFRESH_TOKEN), refreshGrant(ROTATED_REFRESH_TOKEN));
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void rfc7009_postsTheStoredRefreshTokenToTheRevocationEndpoint() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(OK_200, "");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.of(REVOCATION_URL));

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).singleElement().satisfies(request -> {
            assertThat(request.url()).hasToString(REVOCATION_URL);
            assertThat(formParams(request)).containsEntry(TOKEN_PARAM, STORED_REFRESH_TOKEN);
        });
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void grantAlreadyEnded_endsAndDeletesTheStoredToken() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(UNAUTHORIZED_401, """
                                 {"error": "invalid_grant"}""");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(1);
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void transientFailure_isRetried() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(SERVICE_UNAVAILABLE_503, "");
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answerWithToken(LAST_REFRESH_TOKEN);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();
        assertThat(revocation).isNotDone();

        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(1 + ROTATIONS);
    }

    /// The authorisation server was never asked, so the token stays for the caller's own retry.
    @Test
    void retryBudgetSpent_isUnreachableAndKeepsTheStoredToken() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(SERVICE_UNAVAILABLE_503, "");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        // Doubling the budget leaves room for the randomised, growing retry intervals to cross it.
        clock.advanceTimeAndTick(RETRY_MAX_ELAPSED_TIME.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.UNREACHABLE);
        assertThat(requestLog.size()).as("the revocation was retried before giving up").isGreaterThan(1);
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isPresent();
    }

    @Test
    void refusal_isRefusedAndDeletesTheStoredToken() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(BAD_REQUEST_400, """
                                {"error": "invalid_client", "error_description": "client %s unknown"}""".formatted(CLIENT_ID));
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.REFUSED);
        assertThat(requestLog).hasSize(1);
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void rotationAnsweredWithoutARefreshToken_isRefused() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(OK_200, """
                       {"access_token": "at", "expires_in": 3600, "token_type": "Bearer"}""");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.REFUSED);
        assertThat(requestLog).hasSize(1);
    }

    /// A reconnect storing a fresh token while the old grant is being revoked must not lose it.
    @Test
    void aTokenStoredSinceTheRevocationBegan_isLeftInPlace() {
        storeToken(STORED_REFRESH_TOKEN);
        answers.add(_ -> null);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());
        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();
        storeToken("fresh-rt");

        answerPending(UNAUTHORIZED_401, """
                                        {"error": "invalid_grant"}""");
        clock.tick();

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).hasValueSatisfying(
                token -> assertThat(token.refreshToken()).isEqualTo("fresh-rt"));
    }

    @Test
    void stoppingMidRetry_leavesTheRevocationPendingAndTheTokenStored() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(SERVICE_UNAVAILABLE_503, "");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());
        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();
        assertThat(revocation).isNotDone();

        revoker.stop();
        clock.advanceTimeAndTick(RETRY_MAX_ELAPSED_TIME.multipliedBy(2));

        assertThat(revocation).isNotDone();
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isPresent();
    }

    @Test
    void revokeOnAStoppedRevoker_neverCompletes() {
        storeToken(STORED_REFRESH_TOKEN);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());
        revoker.stop();

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isNotDone();
        assertThat(requestLog).isEmpty();
    }

    @Test
    void anAnswerWhoseBodyCannotBeRead_isRetried(@Mock ResponseBody unreadable) throws IOException {
        storeToken(STORED_REFRESH_TOKEN);
        when(unreadable.string()).thenThrow(new IOException("connection reset mid-body"));
        answers.add(request -> response(request, BAD_REQUEST_400, "").newBuilder().body(unreadable).build());
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answerWithToken(LAST_REFRESH_TOKEN);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();
        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(revocation).isCompletedWithValue(Result.ENDED);
        assertThat(requestLog).hasSize(1 + ROTATIONS);
    }

    /// The grant has ended but the token is still stored, so the caller has to learn that the deletion it relies on did not happen.
    @Test
    void aStoreThatCannotDeleteTheToken_failsTheRevocation() {
        storeToken(STORED_REFRESH_TOKEN);
        answerWithToken(ROTATED_REFRESH_TOKEN);
        answerWithToken(LAST_REFRESH_TOKEN);
        VarStore failingStore = spy(varStore);
        doThrow(new IllegalStateException("store unavailable")).when(failingStore).clearValue(VAR_STORE_KEY);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(failingStore);
        clock.tick();

        assertThat(revocation).failsWithin(Duration.ZERO)
                              .withThrowableOfType(ExecutionException.class)
                              .withRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStoreThatCannotDeleteARefusedToken_failsTheRevocation() {
        storeToken(STORED_REFRESH_TOKEN);
        answer(BAD_REQUEST_400, """
                                {"error": "invalid_client"}""");
        VarStore failingStore = spy(varStore);
        doThrow(new IllegalStateException("store unavailable")).when(failingStore).clearValue(VAR_STORE_KEY);
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(failingStore);
        clock.tick();

        assertThat(revocation).failsWithin(Duration.ZERO)
                              .withThrowableOfType(ExecutionException.class)
                              .withRootCauseInstanceOf(IllegalStateException.class);
    }

    /// A store failing to read may be passing, so the token is kept for a later attempt and the failure reaches the caller.
    @Test
    void aStoreThatCannotReadTheToken_failsTheRevocationAndKeepsTheValue() {
        varStore.saveValue(VAR_STORE_KEY, "not-an-encrypted-token");
        StoredGrantRevokerImpl revoker = startRevoker(Optional.empty());

        CompletableFuture<Result> revocation = revoker.revoke(varStore);
        clock.tick();

        assertThat(revocation).isCompletedExceptionally();
        assertThat(varStore.readValue(String.class, VAR_STORE_KEY)).isPresent();
        assertThat(requestLog).isEmpty();
    }

    private StoredGrantRevokerImpl startRevoker(Optional<String> revocationUrl) {
        lenient().when(httpClient.newCall(any())).thenAnswer(invocation -> {
            requestLog.add(invocation.getArgument(0));
            return call;
        });
        lenient().doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0);
            Function<Request, @Nullable Response> answer = answers.size() > 1 ? answers.remove() : checkNotNull(answers.peek());
            Response response = answer.apply(requestLog.getLast());
            if (response == null) {
                pendingCallback = callback;
            } else {
                callback.onResponse(call, response);
            }
            return null;
        }).when(call).enqueue(any());
        var revoker = new StoredGrantRevokerImpl(clock, clock, CLIENT_ID, Optional.of(CLIENT_SECRET), API_NAME, TOKEN_URL, revocationUrl, SCOPE) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        startedRevoker = revoker;
        revoker.start();
        return revoker;
    }

    private void storeToken(String refreshToken) {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("at", refreshToken, NOW.plusSeconds(1800)));
    }

    private void answer(int status, String body) {
        answers.add(request -> response(request, status, body));
    }

    private void answerWithToken(String refreshToken) {
        answer(OK_200, """
                       {"access_token": "at", "refresh_token": "%s", "expires_in": 3600, "token_type": "Bearer"}""".formatted(refreshToken));
    }

    /// Delivers an answer to the request whose answer was held back.
    private void answerPending(int status, String body) {
        Callback callback = checkNotNull(pendingCallback);
        pendingCallback = null;
        asUnchecked(() -> callback.onResponse(call, response(requestLog.getLast(), status, body)));
    }

    private static Map<String, String> refreshGrant(String refreshToken) {
        return Map.of("grant_type", REFRESH_TOKEN, REFRESH_TOKEN, refreshToken, "client_id", CLIENT_ID, "client_secret", CLIENT_SECRET);
    }

    private static Map<String, String> formParams(Request request) {
        var body = (FormBody) checkNotNull(request.body());
        var paramsByName = ImmutableMap.<String, String>builderWithExpectedSize(body.size());
        for (int i = 0; i < body.size(); i++) {
            paramsByName.put(body.name(i), body.value(i));
        }
        return paramsByName.build();
    }
}
