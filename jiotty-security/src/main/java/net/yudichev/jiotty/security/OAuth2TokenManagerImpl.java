package net.yudichev.jiotty.security;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.ObservableValue;
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
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isServerError;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isSuccess;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.Dependency;
import static net.yudichev.jiotty.security.Bindings.LoginPending;
import static net.yudichev.jiotty.security.Bindings.RevocationUrl;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

@SuppressWarnings({"FieldAccessNotGuarded", "NonFinalGuard"}) // @GuardedBy("executor") marks executor confinement, which IntelliJ's lock analysis cannot see
public class OAuth2TokenManagerImpl extends BaseLifecycleComponent implements OAuth2TokenManager {
    /// Token-endpoint error codes saying the grant itself is gone, so no refresh can ever succeed and the user has to authorise again: `invalid_grant`
    /// (RFC 6749 §5.2 — an expired, revoked or already-consumed grant, which is what a password change produces) and the OpenID Connect §3.1.2.6 codes
    /// meaning the end user must interact. Codes naming a client or deployment fault — `invalid_client`, `unauthorized_client`, `invalid_scope`,
    /// `unsupported_grant_type`, `invalid_request` — are deliberately absent: the stored credential may be perfectly good, and dropping it would make every
    /// user of a misconfigured client re-authenticate.
    @VisibleForTesting
    static final ImmutableSet<String> CREDENTIAL_DEAD_ERRORS =
            ImmutableSet.of("invalid_grant", "login_required", "consent_required", "interaction_required", "account_selection_required");
    // Backoff schedule for retrying a failed token request before escalating to PermanentFailure.
    @VisibleForTesting
    static final Duration TOKEN_RETRY_INITIAL_INTERVAL = Duration.ofSeconds(5);
    @VisibleForTesting
    static final Duration TOKEN_RETRY_MAX_ELAPSED_TIME = Duration.ofMinutes(10);
    /// The error Google's revocation endpoint answers with HTTP 400 for a token it no longer recognises, which leaves no grant to revoke.
    @VisibleForTesting
    static final String INVALID_TOKEN_ERROR = "invalid_token";
    /// RFC 6749's name for a refresh token, used as a grant type, a request parameter and an RFC 7009 token type hint.
    @VisibleForTesting
    static final String REFRESH_TOKEN = "refresh_token";
    /// The RFC 7009 revocation request parameter carrying the token to revoke.
    @VisibleForTesting
    static final String TOKEN_PARAM = "token";
    /// The RFC 7009 revocation request parameter naming the kind of token sent.
    @VisibleForTesting
    static final String TOKEN_TYPE_HINT_PARAM = "token_type_hint";
    private static final Duration TOKEN_RETRY_MAX_INTERVAL = Duration.ofMinutes(1);
    protected final Logger logger = LogManager.getLogger(getClass());
    protected final String clientId;
    /// Identifies the API in this manager's log lines, and keys its persisted token. Changing it points the manager at a different key, so every token already
    /// stored under the old one is orphaned — and unrecoverable, since the stored value is sealed with its own key in the AAD. A per-instance discriminator
    /// (a user id, say) belongs in the executor thread name: see [OAuth2TokenManagerModule.Builder#withLogSubjectId].
    protected final String apiName;
    protected final String scope;
    private final Provider<SchedulingExecutor> executorProvider;
    private final VarStore varStore;
    private final Optional<String> clientSecret;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    /// The authentication state. Holds the latest value and hands it to each subscriber on subscription, so a subscriber that attaches once this component has
    /// already started — the common case, as the application starts this component before the service that consumes it — learns the state published during
    /// [#doStart]. [#publishAuthState] and [#subscribeToAccessTokenState] dispatch onto [#executor], which lets this hold the single-threaded implementation.
    @GuardedBy("executor")
    private final ObservableValue<AuthState> authState = ObservableValue.simple(new AuthState.TransientFailure("Initialising"));
    private final String varStoreKey;
    private final String tokenUrl;
    /// The authorisation server's revocation endpoint, or `null` when none is configured, in which case [#revoke()] only deletes the stored token.
    private final @Nullable String revocationUrl;
    /// Whether the owner holds an auth code it supplies via [#onNewAuthCode] as part of its own startup, so a start with no stored token is the beginning of a
    /// login rather than a dormant not-authenticated state (see [#obtainAccessToken]).
    private final boolean loginPending;

    protected SchedulingExecutor executor;
    private OkHttpClient httpClient;
    private OauthAccessToken currentToken;
    /// Backoff governing token-request retries, armed on the first failure of a streak and consulted on each subsequent failure.
    @GuardedBy("executor")
    private BackOff tokenRequestBackOff;
    /// Whether a token-request retry streak is in progress, so the first failure of a streak arms [#tokenRequestBackOff] and a success ends it.
    @GuardedBy("executor")
    private boolean retryingTokenRequest;
    /// The pending scheduled token request — a retry ([#retryTokenRequestOrGiveUp]) or a routine refresh ([#scheduleTokenRefresh]) — or `null` when none is
    /// scheduled. Retained so it is cancelled before the next one is scheduled (at most one is ever pending) and by [#invalidateCredential], which must stop a
    /// pending retry/refresh from resurrecting a dropped credential.
    @GuardedBy("executor")
    private @Nullable Closeable pendingScheduledTokenRequest;
    /// Set by [#revoke()]; afterwards no token request is sent and no token response is stored.
    @GuardedBy("executor")
    private boolean revoked;
    /// Whether a token request has been sent and its answer not yet handled.
    @GuardedBy("executor")
    private boolean tokenRequestInFlight;
    /// The refresh token the request in flight when [#revoke()] ran brings back, or `null` when no revocation waits on one. It completes with `null` when that
    /// request returns no refresh token.
    @GuardedBy("executor")
    private @Nullable CompletableFuture<@Nullable String> inFlightRefreshToken;

    @Inject
    public OAuth2TokenManagerImpl(@Dependency Provider<SchedulingExecutor> executorProvider,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  @Dependency VarStore varStore,
                                  @ClientID String clientId,
                                  @ClientSecret Optional<String> clientSecret,
                                  @ApiName String apiName,
                                  @TokenUrl String tokenUrl,
                                  @RevocationUrl Optional<String> revocationUrl,
                                  @Scope String scope,
                                  @LoginPending boolean loginPending) {
        this.clientId = checkNotNull(clientId);
        this.clientSecret = checkNotNull(clientSecret);
        this.executorProvider = checkNotNull(executorProvider);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.varStore = checkNotNull(varStore);
        this.apiName = checkNotNull(apiName);
        this.scope = checkNotNull(scope);
        this.tokenUrl = checkNotNull(tokenUrl);
        this.revocationUrl = checkNotNull(revocationUrl).orElse(null);
        this.loginPending = loginPending;
        varStoreKey = apiName + "Oauth2Token_" + clientId + "_" + scope;
    }

    @Override
    protected void doStart() {
        httpClient = createHttpClient();
        executor = executorProvider.get();
        tokenRequestBackOff = createRetryBackOff();
        varStore.readValueEncrypted(OauthAccessToken.class, varStoreKey)
                .ifPresentOrElse(accessToken -> {
                                     if (isExpired(accessToken)) {
                                         executor.execute("refreshStoredToken", () -> refreshAccessToken(accessToken.refreshToken()));
                                     } else {
                                         setCurrentToken(accessToken);
                                         scheduleTokenRefresh();
                                     }
                                 },
                                 this::obtainAccessToken);
    }

    /// Handles a start with no usable stored token. With no login pending, the manager stays dormant until a login supplies an auth code, so it publishes
    /// [AuthState.PermanentFailure] to tell the owning integration that the user has to re-authorise. With a login pending, the auth code arrives via
    /// [#onNewAuthCode] as part of the owner's startup and the exchange settles the state to [AuthState.Success] or a failure, so the state stays a
    /// [AuthState.TransientFailure] — publishing the permanent failure would make the owner treat the just-entered credentials as rejected and tear the
    /// login down mid-exchange.
    ///
    /// Subclasses that obtain a token by themselves override this.
    protected void obtainAccessToken() {
        if (loginPending) {
            logger.info("[{}] No valid access token, login pending", apiName);
            publishAuthState(new AuthState.TransientFailure("awaiting login"));
        } else {
            logger.info("[{}] No valid access token, awaiting login authCode ", apiName);
            publishAuthState(new AuthState.PermanentFailure("not authenticated"));
        }
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
        // The pending retry/refresh field is confined to the executor, so release it there. A retry/refresh that fires after this point is dropped by the
        // isStarted() guard on the request path.
        executor.execute(this::cancelPendingTokenRequest);
        Closeable.closeSafelyIfNotNull(logger, () -> shutdown(httpClient));
    }

    @Override
    public Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler) {
        return whenStartedAndNotLifecycling(() -> authState.subscribe(executor, handler));
    }

    /// Publishes `newAuthState` on [#executor], which owns [#authState]. Callers already running there get the same ordering as any other work they queue,
    /// and [#doStart] — which runs on the starting thread — reaches it safely.
    private void publishAuthState(AuthState newAuthState) {
        executor.execute(() -> authState.accept(newAuthState));
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
        RequestBody formBody = formBuilder.build();
        if (!executor.tryExecute("exchangeAuthCode", () -> requestToken(formBody, null))) {
            logger.info("[{}] auth code dropped: the manager has stopped", apiName);
        }
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

    @Override
    public CompletableFuture<Void> revoke() {
        return whenNotLifecycling(() -> isStartedPlain()
                                        ? executor.submit(this::dropCredentialForRevocation)
                                                  .thenCompose(dropped -> requestRevocation(dropped.storedRefreshToken())
                                                                          .thenCombine(dropped.inFlightRefreshToken().thenCompose(this::requestRevocation),
                                                                                       (_, _) -> null))
                                        : new CompletableFuture<>());
    }

    /// Ends this manager's use of its credential: deletes the stored token through [#invalidateCredential], and arranges for the grant a token request still
    /// in flight brings back to be revoked too. Runs on [#executor].
    private DroppedCredential dropCredentialForRevocation() {
        revoked = true;
        CompletableFuture<@Nullable String> refreshTokenInFlight = tokenRequestInFlight ? new CompletableFuture<>() : completedFuture(null);
        inFlightRefreshToken = tokenRequestInFlight ? refreshTokenInFlight : null;
        String storedRefreshToken;
        try {
            storedRefreshToken = varStore.readValueEncrypted(OauthAccessToken.class, varStoreKey).map(OauthAccessToken::refreshToken).orElse(null);
        } finally {
            invalidateCredential("revoked");
        }
        logger.info("[{}] stored token deleted for revocation", apiName);
        return new DroppedCredential(storedRefreshToken, refreshTokenInFlight);
    }

    /// Asks the authorisation server to revoke the grant behind `refreshToken`, retrying a transient failure until the retry budget is spent.
    ///
    /// @param refreshToken the refresh token to revoke, or `null` when there is none, in which case nothing is sent
    private CompletableFuture<Void> requestRevocation(@Nullable String refreshToken) {
        if (refreshToken == null || revocationUrl == null) {
            return completedFuture(null);
        }
        var revocation = new CompletableFuture<Void>();
        sendRevocation(revocationUrl, refreshToken, createRetryBackOff(), revocation);
        return revocation;
    }

    /// The backoff for retrying a failed request to the authorisation server.
    private BackOff createRetryBackOff() {
        // The backoff reads elapsed time through the injected clock (so tests drive it deterministically via ProgrammableClock), not the wall clock.
        return new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(toIntExact(TOKEN_RETRY_INITIAL_INTERVAL.toMillis()))
                .setMaxIntervalMillis(toIntExact(TOKEN_RETRY_MAX_INTERVAL.toMillis()))
                .setMaxElapsedTimeMillis(toIntExact(TOKEN_RETRY_MAX_ELAPSED_TIME.toMillis()))
                .setNanoClock(currentDateTimeProvider)
                .build();
    }

    private void sendRevocation(String url, String refreshToken, BackOff backOff, CompletableFuture<Void> revocation) {
        Request request = new Request.Builder().url(url)
                                               .post(new FormBody.Builder().add(TOKEN_PARAM, refreshToken)
                                                                           .add(TOKEN_TYPE_HINT_PARAM, REFRESH_TOKEN)
                                                                           .build())
                                               .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                onRevocationOutcome(() -> retryRevocationOrFail(url, refreshToken, backOff, revocation, e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                String error;
                try (ResponseBody body = response.body()) {
                    error = response.isSuccessful() ? "" : errorCode(body.string());
                } catch (RuntimeException | IOException e) {
                    onRevocationOutcome(() -> retryRevocationOrFail(url, refreshToken, backOff, revocation, e));
                    return;
                }
                onRevocationOutcome(() -> handleRevocationResponse(url, refreshToken, backOff, revocation, response.code(), error));
            }
        });
    }

    /// Runs `action` on [#executor], dropping it once this manager has stopped, as [#requestToken] does with a token response.
    private void onRevocationOutcome(Runnable action) {
        executor.tryExecute("revocationOutcome", () -> ifNotStopped(action));
    }

    /// Completes `revocation` on a confirmed revocation or a token Google no longer recognises, retries a server-side or rate-limit failure, and fails
    /// `revocation` on any other answer. Runs on [#executor].
    ///
    /// @param error the OAuth error code the response carried, or empty when it carried none
    private void handleRevocationResponse(String url, String refreshToken, BackOff backOff, CompletableFuture<Void> revocation, int status, String error) {
        logger.debug("[{}] grant revocation answered HTTP {}, error '{}'", apiName, status, error);
        if (isSuccess(status) || (status == BAD_REQUEST_400 && INVALID_TOKEN_ERROR.equals(error))) {
            logger.info("[{}] grant revoked", apiName);
            revocation.complete(null);
        } else if (isServerError(status) || status == TOO_MANY_REQUESTS_429) {
            retryRevocationOrFail(url, refreshToken, backOff, revocation, new RuntimeException(apiName + ": grant revocation answered HTTP " + status));
        } else {
            revocation.completeExceptionally(new RuntimeException(apiName + ": grant revocation rejected with HTTP " + status + ", error '" + error + '\''));
        }
    }

    /// Sends the revocation again after a backoff delay, or fails `revocation` with `failure` once the retry budget is spent. Runs on [#executor].
    private void retryRevocationOrFail(String url, String refreshToken, BackOff backOff, CompletableFuture<Void> revocation, Throwable failure) {
        long backOffMillis = backOff.nextBackOffMillis();
        if (backOffMillis == BackOff.STOP) {
            revocation.completeExceptionally(new RuntimeException(apiName + ": grant revocation failed", failure));
        } else {
            logger.info("[{}] grant revocation failed ({}); retrying in {}ms", apiName, humanReadableMessage(failure), backOffMillis);
            executor.schedule(Duration.ofMillis(backOffMillis), () -> sendRevocation(url, refreshToken, backOff, revocation));
        }
    }

    /// @return the OAuth error code `body` carries, or empty when `body` is not an OAuth error response
    private String errorCode(String body) {
        try {
            return Json.parse(body, OauthErrorResponse.class).error();
        } catch (RuntimeException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] grant revocation error response is not an OAuth error: {}", apiName, humanReadableMessageFormattable(e));
            }
            return "";
        }
    }

    private void refreshAccessToken(String refreshToken) {
        var formBuilder = new FormBody.Builder()
                .add("grant_type", REFRESH_TOKEN)
                .add(REFRESH_TOKEN, refreshToken)
                .add("client_id", clientId);
        clientSecret.ifPresent(secret -> formBuilder.add("client_secret", secret));
        requestToken(formBuilder.build(), refreshToken);
    }

    /// Sends a token request and handles its answer on [#executor]. Runs on [#executor].
    private void requestToken(RequestBody formBody, @Nullable String fallbackRefreshToken) {
        if (revoked) {
            logger.info("[{}] token request not sent: the credential is revoked", apiName);
            return;
        }
        tokenRequestInFlight = true;
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

        // If the component stopped while the request was in flight, the orphaned response is dropped instead of being rejected by the dead executor.
        future.whenComplete((responseEither, throwable) -> executor.tryExecute(
                "tokenResponse",
                () -> ifNotStopped(() -> handleTokenResponse(requestTime, formBody, fallbackRefreshToken, responseEither, throwable))));
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
        tokenRequestInFlight = false;
        if (revoked) {
            logger.info("[{}] token response not stored: the credential is revoked", apiName);
            if (inFlightRefreshToken != null) {
                inFlightRefreshToken.complete(responseEither == null
                                              ? null
                                              : responseEither.getLeft().flatMap(OauthAccessTokenResponse::refreshToken).orElse(null));
                inFlightRefreshToken = null;
            }
            return;
        }
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
    /// prior refresh token)
    private void retryTokenRequestOrGiveUp(RequestBody formBody, @Nullable String fallbackRefreshToken, String description) {
        if (!retryingTokenRequest) {
            retryingTokenRequest = true;
            tokenRequestBackOff.reset();
        }
        long backOffMillis = tokenRequestBackOff.nextBackOffMillis();
        if (backOffMillis == BackOff.STOP) {
            retryingTokenRequest = false;
            logger.info("[{}] giving up obtaining token after retrying for {}ms: {}", apiName, tokenRequestBackOff.getMaxElapsedTimeMillis(), description);
            publishAuthState(new AuthState.PermanentFailure(description));
        } else {
            logger.info("[{}] token request failed ({}); retrying in {}ms", apiName, description, backOffMillis);
            publishAuthState(new AuthState.TransientFailure(description));
            scheduleTokenRequest(Duration.ofMillis(backOffMillis), () -> requestToken(formBody, fallbackRefreshToken));
        }
    }

    private void validateTokenType(OauthAccessTokenResponse response) {
        String tokenType = response.tokenType()
                                   .orElseThrow(() -> new IllegalArgumentException(apiName + ": token response missing required 'token_type' field"));
        checkArgument("bearer".equals(tokenType.toLowerCase(Locale.ROOT)),
                      "%s: unsupported token type '%s', only 'Bearer' is supported", apiName, tokenType);
    }

    /// Handles an OAuth error response on [#executor]: a [#CREDENTIAL_DEAD_ERRORS] code means the credential itself is dead, so it is dropped immediately;
    /// every other error (e.g. `temporarily_unavailable`, or a client misconfiguration such as `invalid_client`) is treated like a transport failure —
    /// retried with backoff and, once the retry budget is exhausted, escalated to [AuthState.PermanentFailure] — so a token-endpoint error can never leave
    /// the manager dormant with no token, no pending request and no user-visible state.
    ///
    /// @param fallbackRefreshToken the refresh token re-sent on each retry, or `null` when the failed request was an initial authorization-code exchange
    /// (which carries no prior refresh token)
    private void handleErrorResponse(RequestBody formBody, @Nullable String fallbackRefreshToken, OauthErrorResponse errorResponse) {
        String description = errorResponse.errorDescription().orElse(errorResponse.error());
        logger.info("[{}] token request failed: {} ({})", apiName, errorResponse.error(), description);
        if (CREDENTIAL_DEAD_ERRORS.contains(errorResponse.error())) {
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
        publishAuthState(new AuthState.PermanentFailure(reason));
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
        publishAuthState(new AuthState.Success(currentToken.accessToken()));
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

    /// What [#revoke()] has left to revoke once the stored token is deleted.
    ///
    /// @param storedRefreshToken   the refresh token the deleted token carried, or `null` when no token was stored
    /// @param inFlightRefreshToken completes with the refresh token a token request in flight brings back, or with `null` when none is in flight or it brings
    ///                             none back
    private record DroppedCredential(@Nullable String storedRefreshToken, CompletableFuture<@Nullable String> inFlightRefreshToken) {
        private DroppedCredential {
            checkNotNull(inFlightRefreshToken);
        }
    }
}
