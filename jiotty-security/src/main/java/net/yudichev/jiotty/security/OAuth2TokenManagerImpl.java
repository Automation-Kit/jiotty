package net.yudichev.jiotty.security;

import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.backoff.BackOff;
import net.yudichev.jiotty.common.lang.backoff.ExponentialBackOff;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URLDecoder;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.toIntExact;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.Dependency;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

public class OAuth2TokenManagerImpl extends BaseLifecycleComponent implements OAuth2TokenManager {
    // Backoff schedule for retrying a failed token request before escalating to PermanentFailure.
    @VisibleForTesting
    static final Duration TOKEN_RETRY_INITIAL_INTERVAL = Duration.ofSeconds(5);
    @VisibleForTesting
    static final Duration TOKEN_RETRY_MAX_ELAPSED_TIME = Duration.ofMinutes(10);
    private static final Duration TOKEN_RETRY_MAX_INTERVAL = Duration.ofMinutes(1);
    protected final Logger logger = LogManager.getLogger(getClass());
    protected final String clientId;
    protected final String apiName;
    protected final String scope;
    private final ExecutorFactory executorFactory;
    private final VarStore varStore;
    private final Optional<String> clientSecret;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Listeners<AuthState> listeners = new Listeners<>();
    private final String varStoreKey;
    private final String tokenUrl;

    protected SchedulingExecutor executor;
    private OkHttpClient httpClient;
    private OauthAccessToken currentToken;
    /// Backoff governing token-request retries, armed on the first failure of a streak and consulted on each subsequent failure. Confined to [#executor].
    private BackOff tokenRequestBackOff;
    /// Whether a token-request retry streak is in progress, so the first failure of a streak arms [#tokenRequestBackOff] and a success ends it. Confined to
    /// [#executor].
    private boolean retryingTokenRequest;
    /// The pending scheduled token request — a retry ([#retryTokenRequestOrGiveUp]) or a routine refresh ([#scheduleTokenRefresh]) — or `null` when none is
    /// scheduled. Retained so it is cancelled before the next one is scheduled (at most one is ever pending) and by [#invalidateCredential], which must stop a
    /// pending retry/refresh from resurrecting a dropped credential. Confined to [#executor].
    private @Nullable Closeable pendingScheduledTokenRequest;

    @Inject
    public OAuth2TokenManagerImpl(ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  @Dependency VarStore varStore,
                                  @ClientID String clientId,
                                  @ClientSecret Optional<String> clientSecret,
                                  @ApiName String apiName,
                                  @TokenUrl String tokenUrl,
                                  @Scope String scope) {
        this.clientId = checkNotNull(clientId);
        this.clientSecret = checkNotNull(clientSecret);
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.varStore = checkNotNull(varStore);
        this.apiName = checkNotNull(apiName);
        this.scope = checkNotNull(scope);
        this.tokenUrl = checkNotNull(tokenUrl);
        varStoreKey = apiName + "Oauth2Token_" + clientId + "_" + scope;
    }

    @Override
    protected void doStart() {
        httpClient = createHttpClient();
        // Per-API (often per-user) thread name for log disambiguation; coarse "oauth2" family shared across all token executors.
        executor = executorFactory.createSingleThreadedSchedulingExecutor(apiName + "-oauth2", "oauth2", ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE);
        // The backoff reads elapsed time through the injected clock (so tests drive it deterministically via ProgrammableClock), not the wall clock.
        tokenRequestBackOff = new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(toIntExact(TOKEN_RETRY_INITIAL_INTERVAL.toMillis()))
                .setMaxIntervalMillis(toIntExact(TOKEN_RETRY_MAX_INTERVAL.toMillis()))
                .setMaxElapsedTimeMillis(toIntExact(TOKEN_RETRY_MAX_ELAPSED_TIME.toMillis()))
                .setNanoClock(currentDateTimeProvider)
                .build();
        varStore.readValueEncrypted(OauthAccessToken.class, varStoreKey)
                .ifPresentOrElse(accessToken -> {
                                     if (isExpired(accessToken)) {
                                         refreshAccessToken(accessToken.refreshToken());
                                     } else {
                                         setCurrentToken(accessToken);
                                         scheduleTokenRefresh();
                                     }
                                 },
                                 this::obtainAccessToken);
    }

    protected void obtainAccessToken() {
        logger.info("[{}] No valid access token, awaiting login authCode ", apiName);
    }

    /// Creates the HTTP client used for token requests. Overridden in tests to inject a deterministic fake.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient();
    }

    @Override
    public Optional<String> clientSecret() {
        return clientSecret;
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public String scope() {
        return scope;
    }

    @SuppressWarnings("TypeMayBeWeakened")
    private boolean isExpired(OauthAccessToken accessToken) {
        return currentDateTimeProvider.currentInstant().isAfter(accessToken.expiryTime());
    }

    @Override
    protected void doStop() {
        // Release the pending scheduled retry/refresh on the executor (the field is confined to it); the executor is still live here, and its graceful
        // shutdown below drains this task before terminating.
        executor.execute(this::cancelPendingTokenRequest);
        Closeable.closeSafelyIfNotNull(logger, executor, () -> shutdown(httpClient));
    }

    @Override
    public Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor,
                                                                        () -> Optional.ofNullable(currentToken)
                                                                                      .map(token -> new AuthState.Success(
                                                                                              token.accessToken())),
                                                                        handler));
    }

    public static Map<String, String> splitQuery(String query) {
        var queryValuesByKey = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            queryValuesByKey.put(URLDecoder.decode(pair.substring(0, idx), US_ASCII),
                                 URLDecoder.decode(pair.substring(idx + 1), US_ASCII));
        }
        return queryValuesByKey;
    }

    @Override
    public void onNewAuthCode(String authCode, String redirectUri, Optional<String> codeVerifier) {
        logger.info("[{}] auth code received", apiName);
        var formBuilder = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authCode)
                .add("redirect_uri", redirectUri)
                .add("client_id", clientId);
        clientSecret.ifPresent(secret -> formBuilder.add("client_secret", secret));
        codeVerifier.ifPresent(verifier -> formBuilder.add("code_verifier", verifier));
        requestToken(formBuilder.build(), null);
    }

    @Override
    public void invalidate(String rejectedAccessToken, String reason) {
        whenStartedAndNotLifecycling(() -> executor.execute(() -> {
            // Honour the rejection only for the token the caller actually used and that is still current: a mismatch means the credential has already moved on
            // (no token yet during an in-flight exchange, or a refresh has replaced it), so the rejection is stale and must not drop the live token. Never log
            // the token itself.
            if (currentToken == null || !currentToken.accessToken().equals(rejectedAccessToken)) {
                logger.info("[{}] ignoring stale credential invalidation: {}", apiName, reason);
                return;
            }
            logger.info("[{}] credential invalidated by caller: {}", apiName, reason);
            invalidateCredential(reason);
        }));
    }

    private void refreshAccessToken(String refreshToken) {
        var formBuilder = new FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("client_id", clientId);
        clientSecret.ifPresent(secret -> formBuilder.add("client_secret", secret));
        requestToken(formBuilder.build(), refreshToken);
    }

    private void requestToken(RequestBody formBody, @Nullable String fallbackRefreshToken) {
        logger.info("[{}] requesting token", apiName);
        Request request = new Request.Builder().url(tokenUrl).post(formBody).build();
        Instant requestTime = currentDateTimeProvider.currentInstant();

        var future = new CompletableFuture<Either<OauthAccessTokenResponse, OauthErrorResponse>>();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (ResponseBody body = response.body()) {
                    String bodyString = checkNotNull(body).string();
                    if (response.isSuccessful()) {
                        future.complete(Either.left(Json.parse(bodyString, OauthAccessTokenResponse.class)));
                    } else {
                        try {
                            future.complete(Either.right(Json.parse(bodyString, OauthErrorResponse.class)));
                        } catch (RuntimeException e) {
                            future.completeExceptionally(
                                    new RuntimeException("Token request failed with HTTP " + response.code() + ": " + bodyString, e));
                        }
                    }
                } catch (RuntimeException | IOException e) {
                    future.completeExceptionally(new RuntimeException("Failed to process token response", e));
                }
            }
        });

        // Marshal the completed request back onto the executor under the lifecycle lock: if the component stopped while the request was in flight, orphaned
        // response is dropped instead of being rejected by the dead executor.
        future.whenComplete((responseEither, throwable) ->
                                    whenNotLifecycling(() -> {
                                        if (isStarted()) {
                                            executor.execute(() -> handleTokenResponse(requestTime, formBody, fallbackRefreshToken, responseEither, throwable));
                                        }
                                    }));
    }

    /// Handles a completed token request on [#executor]: a successful response goes to [#handleSuccessResponse], an OAuth error to [#handleErrorResponse], and
    /// any failure — a transport error, or an exception thrown while processing the response — to [#retryTokenRequestOrGiveUp].
    ///
    /// @param responseEither the parsed token response, or `null` when the request failed in transport (then `throwable` is set)
    /// @param throwable      the transport failure, or `null` when the request produced a `responseEither`
    private void handleTokenResponse(Instant requestTime,
                                     RequestBody formBody,
                                     @Nullable String fallbackRefreshToken,
                                     @Nullable Either<OauthAccessTokenResponse, OauthErrorResponse> responseEither,
                                     @Nullable Throwable throwable) {
        Throwable failure = throwable;
        if (failure == null) {
            try {
                assert responseEither != null;
                responseEither.accept(successResponse -> handleSuccessResponse(requestTime, successResponse, fallbackRefreshToken),
                                      errorResponse -> handleErrorResponse(formBody, fallbackRefreshToken, errorResponse));
                return;
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        logger.info("[{}] failed to obtain token", apiName, failure);
        retryTokenRequestOrGiveUp(formBody, fallbackRefreshToken, humanReadableMessage(failure));
    }

    private void handleSuccessResponse(Instant requestTime, OauthAccessTokenResponse response, @Nullable String fallbackRefreshToken) {
        logger.info("[{}] token received", apiName);
        validateTokenType(response);
        OauthAccessToken token = responseToToken(requestTime, response, fallbackRefreshToken);
        varStore.saveValueEncrypted(varStoreKey, token);
        setCurrentToken(token);
        retryingTokenRequest = false;
        scheduleTokenRefresh();
    }

    /// Reschedules a failed token request after a backoff delay, or — once [#tokenRequestBackOff]'s maximum elapsed time is reached — gives up and escalates to
    /// [AuthState.PermanentFailure]. This is the only path that re-drives a request after a failure ([#scheduleTokenRefresh] schedules the routine refresh
    /// solely on success), so a token-endpoint failure keeps being retried here until it recovers or is escalated. The backoff is armed on the first failure of
    /// a streak; a later success ([#handleSuccessResponse]) ends the streak.
    ///
    /// @param fallbackRefreshToken the refresh token re-sent on each retry, or `null` when retrying an initial authorization-code exchange (which carries no
    ///  prior refresh token)
    private void retryTokenRequestOrGiveUp(RequestBody formBody, @Nullable String fallbackRefreshToken, String description) {
        if (!retryingTokenRequest) {
            retryingTokenRequest = true;
            tokenRequestBackOff.reset();
        }
        long backOffMillis = tokenRequestBackOff.nextBackOffMillis();
        if (backOffMillis == BackOff.STOP) {
            retryingTokenRequest = false;
            logger.info("[{}] giving up obtaining token after retrying for {}ms: {}", apiName, tokenRequestBackOff.getMaxElapsedTimeMillis(), description);
            listeners.notify(new AuthState.PermanentFailure(description));
        } else {
            logger.info("[{}] token request failed ({}); retrying in {}ms", apiName, description, backOffMillis);
            listeners.notify(new AuthState.TransientFailure(description));
            scheduleTokenRequest(Duration.ofMillis(backOffMillis), () -> requestToken(formBody, fallbackRefreshToken));
        }
    }

    private void validateTokenType(OauthAccessTokenResponse response) {
        String tokenType = response.tokenType()
                                   .orElseThrow(() -> new IllegalArgumentException(apiName + ": token response missing required 'token_type' field"));
        checkArgument("bearer".equals(tokenType.toLowerCase(Locale.ROOT)),
                      "%s: unsupported token type '%s', only 'Bearer' is supported", apiName, tokenType);
    }

    /// Handles an OAuth error response on [#executor]: `invalid_grant` means the credential itself is dead (revoked/expired refresh token, consumed auth code),
    /// so it is dropped immediately; every other error (e.g. `temporarily_unavailable`) is treated like a transport failure — retried with backoff and, once
    /// the retry budget is exhausted, escalated to [AuthState.PermanentFailure] — so a token-endpoint error can never leave the manager dormant with no token,
    /// no pending request and no user-visible state.
    ///
    /// @param fallbackRefreshToken the refresh token re-sent on each retry, or `null` when the failed request was an initial authorization-code exchange
    ///  (which carries no prior refresh token)
    private void handleErrorResponse(RequestBody formBody, @Nullable String fallbackRefreshToken, OauthErrorResponse errorResponse) {
        String description = errorResponse.errorDescription().orElse(errorResponse.error());
        logger.info("[{}] token request failed: {} ({})", apiName, errorResponse.error(), description);
        if ("invalid_grant".equals(errorResponse.error())) {
            invalidateCredential(description);
        } else {
            retryTokenRequestOrGiveUp(formBody, fallbackRefreshToken, description);
        }
    }

    /// Marks the credential permanently unusable: cancels any pending scheduled retry/refresh, ends the retry streak, and drops the token both in memory and
    /// from the var store — so no scheduled request resurrects the dead credential and a later re-authentication starts clean — then escalates to
    /// [AuthState.PermanentFailure]. Runs on [#executor].
    private void invalidateCredential(String reason) {
        cancelPendingTokenRequest();
        retryingTokenRequest = false;
        currentToken = null;
        varStore.clearValue(varStoreKey);
        listeners.notify(new AuthState.PermanentFailure(reason));
    }

    private OauthAccessToken responseToToken(Instant currentTime, OauthAccessTokenResponse response, @Nullable String fallbackRefreshToken) {
        String refreshToken = response.refreshToken().orElse(fallbackRefreshToken);
        checkArgument(refreshToken != null,
                      "%s: token response missing 'refresh_token' and no existing refresh token available", apiName);

        Instant expiryTime = currentTime.plusSeconds(response.expiresInSec());
        Instant refreshTime = currentTime.plusSeconds((long) response.expiresInSec() * 8 / 10);
        Duration bufferBeforeExpiry = Duration.between(refreshTime, expiryTime);
        checkArgument(bufferBeforeExpiry.compareTo(Duration.ofMinutes(1)) >= 0,
                      """
                      %s: OAuth2 response token's 'expires_in' value %s is too small:\
                      the resulting token refresh time %s is too close to expiry time %s""",
                      apiName, response.expiresInSec(), refreshTime, expiryTime);
        return OauthAccessToken.of(response.accessToken(), refreshToken, refreshTime);
    }

    protected void setCurrentToken(OauthAccessToken accessToken) {
        logger.debug("[{}] token set, expires at {}", apiName, accessToken.expiryTime());
        currentToken = accessToken;
        listeners.notify(new AuthState.Success(currentToken.accessToken()));
    }

    private void scheduleTokenRefresh() {
        Duration expiryDelay = Duration.between(currentDateTimeProvider.currentInstant(), currentToken.expiryTime());
        logger.info("[{}] will refresh token in {} ({})", apiName, expiryDelay, currentToken.expiryTime());
        String refreshToken = currentToken.refreshToken();
        scheduleTokenRequest(expiryDelay, () -> refreshAccessToken(refreshToken));
    }

    /// Schedules `command` after `delay` as the single pending token request, cancelling any previously-scheduled retry/refresh first — so at most one is ever
    /// pending and [#invalidateCredential] can stop it. Runs on [#executor].
    private void scheduleTokenRequest(Duration delay, Runnable command) {
        cancelPendingTokenRequest();
        pendingScheduledTokenRequest = executor.schedule(delay, command);
    }

    private void cancelPendingTokenRequest() {
        Closeable.closeSafelyIfNotNull(logger, pendingScheduledTokenRequest);
        pendingScheduledTokenRequest = null;
    }
}
