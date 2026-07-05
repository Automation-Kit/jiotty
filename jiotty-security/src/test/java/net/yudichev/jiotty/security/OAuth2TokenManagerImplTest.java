package net.yudichev.jiotty.security;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStoreEncryption;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.TOKEN_RETRY_INITIAL_INTERVAL;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.TOKEN_RETRY_MAX_ELAPSED_TIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OAuth2TokenManagerImplTest {
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String API_NAME = "testApi";
    private static final String SCOPE = "test-scope";
    private static final String TOKEN_URL = "http://token-host/token";
    private static final String VAR_STORE_KEY = API_NAME + "Oauth2Token_" + CLIENT_ID + "_" + SCOPE;
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final MediaType APPLICATION_JSON = MediaType.parse("application/json");
    /// Sentinel stub: the enqueue answer delivers it as an okhttp transport failure ([Callback#onFailure]) rather than an HTTP response.
    private static final FakeResponse TRANSPORT_FAILURE = new FakeResponse(-1, "");

    private final ProgrammableClock clock = new ProgrammableClock();
    private final InMemoryVarStore varStore = new InMemoryVarStore();
    // Every Request the manager sends, in order, so tests can assert form params / URL on what was actually sent.
    private final List<Request> requestLog = new ArrayList<>();
    // The token-endpoint responses delivered to successive requests; the last entry is reused once exhausted, so a streak of retries keeps getting it.
    private final List<FakeResponse> responses = new ArrayList<>();
    // Auth states the manager publishes, captured in order (the manager runs on the single-threaded ProgrammableClock executor, so a plain list is safe).
    private final List<AuthState> authStates = new ArrayList<>();
    private int responseIndex;
    // Set while the manager is starting (and by the stopped-manager test): the okhttp callback is captured rather than answered synchronously, so the response
    // lands after start() returns — mirroring the real async okhttp call, which never completes mid-doStart.
    private boolean captureOnly;
    private @Nullable Callback pendingCallback;
    private @Nullable Request pendingRequest;
    @Mock
    private OkHttpClient httpClient;
    @Mock
    private Call call;
    private OAuth2TokenManagerImpl tokenManager;

    @BeforeEach
    void setUp() {
        clock.setTimeAndTick(NOW);
        // Each newCall records the request and returns the shared Call; its enqueue answers with the next stubbed response (synchronously by default, or
        // captured for later when captureOnly is set). lenient(): the valid-stored-token test makes no HTTP call and so never exercises these stubs.
        lenient().when(httpClient.newCall(any())).thenAnswer(invocation -> {
            requestLog.add(invocation.getArgument(0));
            return call;
        });
        lenient().doAnswer(invocation -> {
            Callback callback = invocation.getArgument(0);
            Request request = requestLog.getLast();
            if (captureOnly) {
                pendingCallback = callback;
                pendingRequest = request;
            } else {
                deliver(callback, request);
            }
            return null;
        }).when(call).enqueue(any());
    }

    @Test
    void authCodeExchange_sendsCorrectFormParamsAndDeliversToken() {
        respondWithToken("test-access-token", "test-refresh-token", 3600);
        startTokenManager();

        tokenManager.onNewAuthCode("auth-code-123", "http://localhost/callback");
        clock.tick();

        assertThat(formParams(soleRequest())).containsEntry("grant_type", "authorization_code")
                                             .containsEntry("code", "auth-code-123")
                                             .containsEntry("redirect_uri", "http://localhost/callback")
                                             .containsEntry("client_id", CLIENT_ID)
                                             .containsEntry("client_secret", CLIENT_SECRET)
                                             .doesNotContainKey("refresh_token")
                                             .doesNotContainKey("code_verifier");
        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("test-access-token"));
    }

    @Test
    void authCodeExchange_publicClientWithPkce_sendsCodeVerifierAndOmitsSecret() {
        respondWithToken("at", "rt", 3600);
        startTokenManager(Optional.empty());

        tokenManager.onNewAuthCode("auth-code-123", "http://localhost/callback", Optional.of("pkce-verifier"));
        clock.tick();

        assertThat(formParams(soleRequest())).containsEntry("grant_type", "authorization_code")
                                             .containsEntry("code", "auth-code-123")
                                             .containsEntry("redirect_uri", "http://localhost/callback")
                                             .containsEntry("client_id", CLIENT_ID)
                                             .containsEntry("code_verifier", "pkce-verifier")
                                             .doesNotContainKey("client_secret");
        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("at"));
    }

    @Test
    void refresh_publicClient_omitsClientSecret() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("old-at", "old-rt", NOW.minusSeconds(60)));
        respondWithToken("new-at", "new-rt", 7200);

        startTokenManager(Optional.empty());

        assertThat(formParams(soleRequest())).containsEntry("grant_type", "refresh_token")
                                             .containsEntry("refresh_token", "old-rt")
                                             .containsEntry("client_id", CLIENT_ID)
                                             .doesNotContainKey("client_secret");
    }

    @Test
    void authCodeExchange_persistsTokenToVarStore() {
        respondWithToken("at-1", "rt-1", 3600);
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://redirect");
        clock.tick();

        // expires_in=3600, refreshTime = NOW + 3600 * 8 / 10 = NOW + 2880
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("at-1", "rt-1", NOW.plusSeconds(2880)));
        // The credential is persisted encrypted at rest, not as plaintext JSON.
        assertThat(varStore.rawStoredValue(VAR_STORE_KEY)).hasValueSatisfying(stored ->
                                                                                      assertThat(VarStoreEncryption.isEnvelope(stored)).as(
                                                                                              "token stored as encryption envelope").isTrue());
    }

    @Test
    void startupWithValidStoredToken_doesNotHitServer() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("stored-at", "stored-rt", NOW.plusSeconds(1800)));

        startTokenManager();

        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("stored-at"));
        assertThat(requestLog).isEmpty();
    }

    @Test
    void startupWithExpiredStoredToken_triggersRefresh() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("old-at", "old-rt", NOW.minusSeconds(60)));
        respondWithToken("new-at", "new-rt", 7200);

        startTokenManager();

        assertThat(formParams(soleRequest())).containsEntry("grant_type", "refresh_token")
                                             .containsEntry("refresh_token", "old-rt")
                                             .containsEntry("client_id", CLIENT_ID)
                                             .containsEntry("client_secret", CLIENT_SECRET)
                                             .doesNotContainKey("code")
                                             .doesNotContainKey("redirect_uri");
        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("new-at"));
    }

    @Test
    void refreshTimeCalculation_refreshesAtEightyPercentOfLifetime() {
        respondWithToken("at", "rt", 1000);
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        // expires_in=1000, refreshTime = NOW + 1000 * 8 / 10 = NOW + 800
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("at", "rt", NOW.plusSeconds(800)));
    }

    @Test
    void refreshResponse_withoutRefreshToken_keepsPreviousRefreshToken() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("old-at", "original-rt", NOW.minusSeconds(60)));
        responses.add(new FakeResponse(200, """
                                            {"access_token": "new-at", "expires_in": 3600, "token_type": "Bearer"}"""));

        startTokenManager();

        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("new-at", "original-rt", NOW.plusSeconds(2880)));
    }

    @Test
    void tokenTypeValidation_acceptsBearerCaseInsensitive() {
        responses.add(new FakeResponse(200, """
                                            {"access_token": "at", "refresh_token": "rt", "expires_in": 3600, "token_type": "BEARER"}"""));
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("at"));
    }

    @Test
    void tokenTypeValidation_rejectsNonBearerType() {
        responses.add(new FakeResponse(200, """
                                            {"access_token": "at", "refresh_token": "rt", "expires_in": 3600, "token_type": "MAC"}"""));
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOf(AuthState.TransientFailure.class);
    }

    @Test
    void errorResponse_invalidGrant_notifiesLoginRequired() {
        respondWith(400, """
                         {"error": "invalid_grant", "error_description": "Token has been revoked"}""");
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOfSatisfying(
                AuthState.PermanentFailure.class,
                permanentFailure -> assertThat(permanentFailure.description()).isEqualTo("Token has been revoked"));
    }

    @Test
    void errorResponse_otherError_notifiesTransientError() {
        respondWith(400, """
                         {"error": "invalid_client", "error_description": "Client authentication failed"}""");
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOfSatisfying(
                AuthState.TransientFailure.class,
                transientFailure -> assertThat(transientFailure.description()).isEqualTo("Client authentication failed"));
    }

    @Test
    void tokenRequestFailure_retriesAndRecoversOnNextAttempt() {
        // A token whose lifetime is too short to schedule a refresh before it expires makes the first attempt fail; the retry then gets a healthy token.
        respondWithToken("at-fail", "rt", 10);
        respondWithToken("at-ok", "rt", 3600);
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOf(AuthState.TransientFailure.class);

        // The retry is scheduled one (randomised) initial interval out; doubling it clears the +50% jitter ceiling so the retry reliably fires.
        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(lastAuthState()).isInstanceOfSatisfying(AuthState.Success.class,
                                                           success -> assertThat(success.authInfo()).isEqualTo("at-ok"));
        // the endpoint was hit twice: the initial failed attempt and the successful retry
        assertThat(requestLog).hasSize(2);
    }

    @Test
    void tokenRequestFailure_keepsFailing_givesUpWithPermanentFailure() {
        // Every attempt returns a token too short-lived to use, so the attempts keep failing until the retry budget is exhausted.
        respondWithToken("at", "rt", 10);
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();
        assertThat(lastAuthState()).isInstanceOf(AuthState.TransientFailure.class);

        // Advancing well past the retry budget (its max elapsed time) lets the scheduled retries run until one finds the budget exhausted and escalates to a
        // re-auth prompt; doubling the window leaves ample room for the randomised, growing retry intervals to cross the threshold.
        clock.advanceTimeAndTick(TOKEN_RETRY_MAX_ELAPSED_TIME.multipliedBy(2));

        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);
    }

    @Test
    void transportFailure_reportedAsTransientFailure() {
        respondWithTransportFailure();
        startTokenManager();

        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOf(AuthState.TransientFailure.class);
    }

    @Test
    void invalidGrantOnStoredTokenRefresh_clearsStoredTokenSoReconnectStartsClean() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("old-at", "old-rt", NOW.minusSeconds(60)));
        respondWith(400, """
                         {"error": "invalid_grant", "error_description": "Token has been expired or revoked."}""");

        // start() refreshes the expired stored token, which Google rejects as invalid_grant
        startTokenManager();

        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);
        // the dead credential is dropped, so a later reconnect's startup finds no token to refresh (no doomed refresh racing the fresh auth-code exchange)
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void invalidate_dropsStoredTokenAndNotifiesPermanentFailure() {
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("stored-at", "stored-rt", NOW.plusSeconds(1800)));
        startTokenManager();
        assertThat(lastAuthState()).isInstanceOf(AuthState.Success.class);

        tokenManager.invalidate("API rejected the token");
        clock.tick();

        assertThat(lastAuthState()).isInstanceOfSatisfying(
                AuthState.PermanentFailure.class,
                permanentFailure -> assertThat(permanentFailure.description()).isEqualTo("API rejected the token"));
        // the dropped credential must not be refreshed by a later startup
        assertThat(varStore.readValueEncrypted(OauthAccessToken.class, VAR_STORE_KEY)).isEmpty();
    }

    @Test
    void invalidate_preventsScheduledRefreshFromResurrectingTheCredential() {
        // A valid stored token schedules a refresh at 80% of its lifetime (800s); invalidate() before then must stop that refresh from re-authenticating.
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("stored-at", "stored-rt", NOW.plusSeconds(1000)));
        respondWithToken("refreshed-at", "refreshed-rt", 3600);
        startTokenManager();
        assertThat(requestLog).isEmpty(); // a valid stored token needs no token request at startup

        tokenManager.invalidate("API rejected the token");
        clock.tick();
        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);

        // Advance past the scheduled refresh time: the refresh must not fire (no token request) and the state must stay PermanentFailure.
        clock.advanceTimeAndTick(Duration.ofSeconds(1000));

        assertThat(requestLog).isEmpty();
        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);
    }

    @Test
    void invalidate_cancelsPendingRetrySoItDoesNotResurrectTheCredential() {
        // The first attempt returns a token too short-lived to use, so it fails and a retry is scheduled; the second response would succeed if the retry fired.
        respondWithToken("at-fail", "rt", 10);
        respondWithToken("at-ok", "rt", 3600);
        startTokenManager();
        tokenManager.onNewAuthCode("code", "http://r");
        clock.tick();
        assertThat(lastAuthState()).isInstanceOf(AuthState.TransientFailure.class);
        assertThat(requestLog).hasSize(1); // the failed attempt; a retry is now scheduled

        tokenManager.invalidate("API rejected the token");
        clock.tick();
        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);

        // Advancing past the retry delay must NOT fire the cancelled retry: no second request, and the state stays PermanentFailure (the "at-ok" response is
        // never consumed, so the credential is not resurrected).
        clock.advanceTimeAndTick(TOKEN_RETRY_INITIAL_INTERVAL.multipliedBy(2));

        assertThat(requestLog).hasSize(1);
        assertThat(lastAuthState()).isInstanceOf(AuthState.PermanentFailure.class);
    }

    @Test
    void stop_cancelsPendingScheduledRefreshSoItDoesNotFireAfterShutdown() {
        // A valid stored token schedules a refresh at 80% of its lifetime; stopping the manager before then must cancel it so it never runs post-shutdown.
        varStore.saveValueEncrypted(VAR_STORE_KEY, OauthAccessToken.of("stored-at", "stored-rt", NOW.plusSeconds(1000)));
        respondWithToken("refreshed-at", "refreshed-rt", 3600);
        startTokenManager();
        assertThat(requestLog).isEmpty(); // a valid stored token needs no token request at startup

        tokenManager.stop();

        // Advance well past the scheduled refresh time: the cancelled refresh must not fire, so no token request is ever made.
        clock.advanceTimeAndTick(Duration.ofSeconds(1000));

        assertThat(requestLog).isEmpty();
    }

    @Test
    void tokenResponseAfterStop_isDroppedWithoutPublishingState() {
        respondWithToken("at", "rt", 3600);
        startTokenManager();

        captureOnly = true;
        tokenManager.onNewAuthCode("code", "http://r");
        captureOnly = false;
        tokenManager.stop();

        // the in-flight request's callback lands after the component stopped (e.g. the integration was torn down); it must be dropped, not processed
        deliverPending();
        clock.tick();

        assertThat(authStates).noneMatch(AuthState.Success.class::isInstance);
    }

    private void startTokenManager() {
        startTokenManager(Optional.of(CLIENT_SECRET));
    }

    private void startTokenManager(Optional<String> clientSecret) {
        tokenManager = new OAuth2TokenManagerImpl(clock, clock, varStore, CLIENT_ID, clientSecret, API_NAME, TOKEN_URL, SCOPE) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        // A token request issued during start() (refreshing an expired stored token) is answered only after start() returns — mirroring the real async okhttp
        // call, which never lands mid-doStart — so the component is fully started when the response arrives.
        captureOnly = true;
        tokenManager.start();
        captureOnly = false;
        tokenManager.subscribeToAccessTokenState(authStates::add);
        if (pendingCallback != null) {
            deliverPending();
        }
        clock.tick();
    }

    private void deliver(Callback callback, Request request) {
        if (responses.isEmpty()) {
            return;
        }
        FakeResponse response = responses.get(Math.min(responseIndex++, responses.size() - 1));
        asUnchecked(() -> {
            if (response == TRANSPORT_FAILURE) {
                callback.onFailure(call, new IOException("simulated transport failure"));
            } else {
                callback.onResponse(call, fakeResponse(request, response.status(), response.body()));
            }
        });
    }

    /// Answers the request captured while [#captureOnly] was set (one issued during start, or in the stopped-manager test).
    private void deliverPending() {
        deliver(checkNotNull(pendingCallback), checkNotNull(pendingRequest));
        pendingCallback = null;
        pendingRequest = null;
    }

    private void respondWithToken(String accessToken, String refreshToken, int expiresIn) {
        responses.add(new FakeResponse(200, """
                                            {"access_token": "%s", "refresh_token": "%s", "expires_in": %d, "token_type": "Bearer"}"""
                .formatted(accessToken, refreshToken, expiresIn)));
    }

    private void respondWith(int status, String body) {
        responses.add(new FakeResponse(status, body));
    }

    private void respondWithTransportFailure() {
        responses.add(TRANSPORT_FAILURE);
    }

    private Request soleRequest() {
        assertThat(requestLog).hasSize(1);
        return requestLog.getFirst();
    }

    private AuthState lastAuthState() {
        assertThat(authStates).isNotEmpty();
        return authStates.getLast();
    }

    private static Map<String, String> formParams(Request request) {
        var body = (FormBody) checkNotNull(request.body());
        var paramsByName = ImmutableMap.<String, String>builderWithExpectedSize(body.size());
        for (int i = 0; i < body.size(); i++) {
            paramsByName.put(body.name(i), body.value(i));
        }
        return paramsByName.build();
    }

    private static Response fakeResponse(Request request, int status, String json) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(status == 200 ? "OK" : "Error")
                .body(ResponseBody.create(json, APPLICATION_JSON))
                .build();
    }

    private record FakeResponse(int status, String body) {}
}
