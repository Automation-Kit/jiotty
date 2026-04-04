package net.yudichev.jiotty.security;

import net.yudichev.jiotty.common.async.ExecutorFactoryImpl;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.rest.JavalinRestServer;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2TokenManagerImplTest {
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String API_NAME = "testApi";
    private static final String SCOPE = "test-scope";
    private static final String VAR_STORE_KEY = API_NAME + "Oauth2Token_" + CLIENT_ID + "_" + SCOPE;
    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private final InMemoryVarStore varStore = new InMemoryVarStore();
    @Mock
    private CurrentDateTimeProvider timeProvider;
    private JavalinRestServer fakeTokenServer;
    private OAuth2TokenManagerImpl tokenManager;
    private LinkedBlockingQueue<Map<String, String>> capturedRequests;
    private LinkedBlockingQueue<FakeResponse> responseQueue;
    private LinkedBlockingQueue<AuthState> tokenResults;

    @BeforeEach
    void setUp() {
        when(timeProvider.currentInstant()).thenReturn(NOW);

        capturedRequests = new LinkedBlockingQueue<>();
        responseQueue = new LinkedBlockingQueue<>();
        tokenResults = new LinkedBlockingQueue<>();

        fakeTokenServer = new JavalinRestServer(0);
        fakeTokenServer.post("/token", ctx -> {
            Map<String, String> params = new LinkedHashMap<>();
            ctx.formParamMap().forEach((key, values) -> {
                if (!values.isEmpty()) {
                    params.put(key, values.getFirst());
                }
            });
            capturedRequests.add(params);

            FakeResponse fakeResponse = getAsUnchecked(() -> responseQueue.poll(5, SECONDS));
            ctx.status(fakeResponse.statusCode());
            ctx.contentType("application/json");
            ctx.result(fakeResponse.body());
        });
        fakeTokenServer.start();
    }

    @AfterEach
    void tearDown() {
        Closeable.closeIfNotNull(
                () -> {
                    if (tokenManager != null) {
                        tokenManager.stop();
                    }
                },
                fakeTokenServer::stop);
    }

    @Test
    void authCodeExchange_sendsCorrectFormParamsAndDeliversToken() {
        createAndStartTokenManager();
        enqueueSuccessResponse("test-access-token", "test-refresh-token", 3600);

        tokenManager.onNewAuthCode("auth-code-123", "http://localhost/callback");

        Map<String, String> params = pollCapturedRequest();
        assertThat(params).containsEntry("grant_type", "authorization_code")
                          .containsEntry("code", "auth-code-123")
                          .containsEntry("redirect_uri", "http://localhost/callback")
                          .containsEntry("client_id", CLIENT_ID)
                          .containsEntry("client_secret", CLIENT_SECRET)
                          .doesNotContainKey("refresh_token");

        assertThat(pollTokenResult()).isInstanceOfSatisfying(AuthState.Success.class, success -> assertThat(success.authInfo()).isEqualTo("test-access-token"));
    }

    @Test
    void authCodeExchange_persistsTokenToVarStore() {
        createAndStartTokenManager();
        enqueueSuccessResponse("at-1", "rt-1", 3600);

        tokenManager.onNewAuthCode("code", "http://redirect");
        pollTokenResult();

        // expires_in=3600, refreshTime = NOW + 3600 * 8 / 10 = NOW + 2880
        Instant expectedRefreshTime = NOW.plusSeconds(2880);
        assertThat(varStore.readValue(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("at-1", "rt-1", expectedRefreshTime));
    }

    @Test
    void startupWithValidStoredToken_doesNotHitServer() {
        Instant futureRefreshTime = NOW.plusSeconds(1800);
        varStore.saveValue(VAR_STORE_KEY, OauthAccessToken.of("stored-at", "stored-rt", futureRefreshTime));

        createAndStartTokenManager();

        assertThat(pollTokenResult()).isInstanceOfSatisfying(AuthState.Success.class, success -> assertThat(success.authInfo()).isEqualTo("stored-at"));
        assertThat(capturedRequests).isEmpty();
    }

    @Test
    void startupWithExpiredStoredToken_triggersRefresh() {
        Instant pastRefreshTime = NOW.minusSeconds(60);
        varStore.saveValue(VAR_STORE_KEY, OauthAccessToken.of("old-at", "old-rt", pastRefreshTime));

        enqueueSuccessResponse("new-at", "new-rt", 7200);
        createAndStartTokenManager();

        Map<String, String> params = pollCapturedRequest();
        assertThat(params).containsEntry("grant_type", "refresh_token")
                          .containsEntry("refresh_token", "old-rt")
                          .containsEntry("client_id", CLIENT_ID)
                          .containsEntry("client_secret", CLIENT_SECRET)
                          .doesNotContainKey("code")
                          .doesNotContainKey("redirect_uri");

        assertThat(pollTokenResult()).isInstanceOfSatisfying(AuthState.Success.class, success -> assertThat(success.authInfo()).isEqualTo("new-at"));
    }

    @Test
    void refreshTimeCalculation_refreshesAtEightyPercentOfLifetime() {
        createAndStartTokenManager();
        enqueueSuccessResponse("at", "rt", 1000);

        tokenManager.onNewAuthCode("code", "http://r");
        pollTokenResult();

        // expires_in=1000, refreshTime = NOW + 1000 * 8 / 10 = NOW + 800
        Instant expectedRefreshTime = NOW.plusSeconds(800);
        assertThat(varStore.readValue(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("at", "rt", expectedRefreshTime));
    }

    @Test
    void refreshResponse_withoutRefreshToken_keepsPreviousRefreshToken() {
        Instant pastRefreshTime = NOW.minusSeconds(60);
        varStore.saveValue(VAR_STORE_KEY, OauthAccessToken.of("old-at", "original-rt", pastRefreshTime));

        responseQueue.add(new FakeResponse(200, """
                                                {"access_token": "new-at", "expires_in": 3600, "token_type": "Bearer"}"""));
        createAndStartTokenManager();
        pollTokenResult();

        Instant expectedRefreshTime = NOW.plusSeconds(2880);
        assertThat(varStore.readValue(OauthAccessToken.class, VAR_STORE_KEY))
                .hasValue(OauthAccessToken.of("new-at", "original-rt", expectedRefreshTime));
    }

    @Test
    void tokenTypeValidation_acceptsBearerCaseInsensitive() {
        createAndStartTokenManager();
        responseQueue.add(new FakeResponse(200, """
                                                {"access_token": "at", "refresh_token": "rt", "expires_in": 3600, "token_type": "BEARER"}"""));

        tokenManager.onNewAuthCode("code", "http://r");

        assertThat(pollTokenResult()).isInstanceOfSatisfying(AuthState.Success.class, success -> assertThat(success.authInfo()).isEqualTo("at"));
    }

    @Test
    void tokenTypeValidation_rejectsNonBearerType() {
        createAndStartTokenManager();
        responseQueue.add(new FakeResponse(200, """
                                                {"access_token": "at", "refresh_token": "rt", "expires_in": 3600, "token_type": "MAC"}"""));

        tokenManager.onNewAuthCode("code", "http://r");

        assertThat(pollTokenResult()).isInstanceOf(AuthState.TransientFailure.class);
    }

    @Test
    void errorResponse_invalidGrant_notifiesLoginRequired() {
        createAndStartTokenManager();
        responseQueue.add(new FakeResponse(400, """
                                                {"error": "invalid_grant", "error_description": "Token has been revoked"}"""));

        tokenManager.onNewAuthCode("code", "http://r");

        assertThat(pollTokenResult()).isInstanceOfSatisfying(
                AuthState.PermanentFailure.class,
                permanentFailure -> assertThat(permanentFailure.description()).isEqualTo("Token has been revoked"));
    }

    @Test
    void errorResponse_otherError_notifiesTransientError() {
        createAndStartTokenManager();
        responseQueue.add(new FakeResponse(400, """
                                                {"error": "invalid_client", "error_description": "Client authentication failed"}"""));

        tokenManager.onNewAuthCode("code", "http://r");

        assertThat(pollTokenResult()).isInstanceOfSatisfying(
                AuthState.TransientFailure.class,
                transientFailure -> assertThat(transientFailure.description()).isEqualTo("Client authentication failed"));
    }

    private void createAndStartTokenManager() {
        tokenManager = new OAuth2TokenManagerImpl(
                new ExecutorFactoryImpl(), timeProvider, varStore,
                CLIENT_ID, CLIENT_SECRET, API_NAME,
                "http://localhost:" + fakeTokenServer.port() + "/token",
                SCOPE);
        tokenManager.start();
        tokenManager.subscribeToAccessTokenState(tokenResults::add);
    }

    private void enqueueSuccessResponse(String accessToken, String refreshToken, int expiresIn) {
        responseQueue.add(new FakeResponse(200, """
                                                {"access_token": "%s", "refresh_token": "%s", "expires_in": %d, "token_type": "Bearer"}"""
                .formatted(accessToken, refreshToken, expiresIn)));
    }

    private Map<String, String> pollCapturedRequest() {
        return getAsUnchecked(() -> capturedRequests.poll(5, SECONDS));
    }

    private AuthState pollTokenResult() {
        return getAsUnchecked(() -> tokenResults.poll(5, SECONDS));
    }

    private record FakeResponse(int statusCode, String body) {}
}
