package net.yudichev.jiotty.security;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import static java.nio.charset.StandardCharsets.US_ASCII;
import static net.yudichev.jiotty.common.lang.CompletableFutures.logErrorOnFailure;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

public class OAuth2TokenManagerImpl extends BaseLifecycleComponent implements OAuth2TokenManager {
    protected final Logger logger = LogManager.getLogger(getClass());

    protected final String clientId;
    protected final String apiName;
    protected final String scope;
    private final ExecutorFactory executorFactory;
    private final VarStore varStore;
    private final String clientSecret;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Listeners<AuthState> listeners = new Listeners<>();
    private final String varStoreKey;
    private final String tokenUrl;

    protected SchedulingExecutor executor;
    private OkHttpClient httpClient;
    private OauthAccessToken currentToken;

    @Inject
    public OAuth2TokenManagerImpl(ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  VarStore varStore,
                                  @ClientID String clientId,
                                  @ClientSecret String clientSecret,
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
        httpClient = newClient();
        executor = executorFactory.createSingleThreadedSchedulingExecutor(apiName + "-oauth2");
        varStore.readValue(OauthAccessToken.class, varStoreKey)
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

    @Override
    public String clientSecret() {
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
        Closeable.closeSafelyIfNotNull(logger, executor, () -> shutdown(httpClient));
    }

    @Override
    public Closeable subscribeToAccessTokenState(Consumer<? super AuthState> handler) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor,
                                                                        () -> Optional.ofNullable(currentToken)
                                                                                      .map(token -> new AuthState.Success(token.accessToken())),
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
    public void onNewAuthCode(String authCode, String redirectUri) {
        logger.info("[{}] auth code received", apiName);
        requestToken(new FormBody.Builder()
                             .add("grant_type", "authorization_code")
                             .add("code", authCode)
                             .add("redirect_uri", redirectUri)
                             .add("client_id", clientId)
                             .add("client_secret", clientSecret)
                             .build(),
                     null);
    }

    private void refreshAccessToken(String refreshToken) {
        requestToken(new FormBody.Builder()
                             .add("grant_type", "refresh_token")
                             .add("refresh_token", refreshToken)
                             .add("client_id", clientId)
                             .add("client_secret", clientSecret)
                             .build(),
                     refreshToken);
    }

    private void requestToken(RequestBody formBody, @Nullable String fallbackRefreshToken) {
        logger.info("[{}] requesting token", apiName);
        Request request = new Request.Builder().url(tokenUrl).post(formBody).build();
        Instant requestTime = currentDateTimeProvider.currentInstant();

        var future = new CompletableFuture<Either<OauthAccessTokenResponse, OauthErrorResponse>>();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
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

        future.thenAcceptAsync(responseEither -> responseEither.accept(
                                       successResponse -> handleSuccessResponse(requestTime, successResponse, fallbackRefreshToken),
                                       this::handleErrorResponse),
                               executor)
              .exceptionallyAsync(exception -> {
                  logger.info("[{}] failed to obtain token", apiName, exception);
                  listeners.notify(new AuthState.TransientFailure(HumanReadableExceptionMessage.humanReadableMessage(exception)));
                  return null;
              }, executor)
              .whenComplete(logErrorOnFailure(logger, "[%s] Unhandled exception", apiName));
    }

    private void handleSuccessResponse(Instant requestTime, OauthAccessTokenResponse response, @Nullable String fallbackRefreshToken) {
        logger.info("[{}] token received", apiName);
        validateTokenType(response);
        OauthAccessToken token = responseToToken(requestTime, response, fallbackRefreshToken);
        varStore.saveValue(varStoreKey, token);
        setCurrentToken(token);
        scheduleTokenRefresh();
    }

    private void validateTokenType(OauthAccessTokenResponse response) {
        String tokenType = response.tokenType()
                                   .orElseThrow(() -> new IllegalArgumentException(apiName + ": token response missing required 'token_type' field"));
        checkArgument("bearer".equals(tokenType.toLowerCase(Locale.ROOT)),
                      "%s: unsupported token type '%s', only 'Bearer' is supported", apiName, tokenType);
    }

    private void handleErrorResponse(OauthErrorResponse errorResponse) {
        String description = errorResponse.errorDescription().orElse(errorResponse.error());
        logger.info("[{}] token request failed: {} ({})", apiName, errorResponse.error(), description);
        AuthState failure = "invalid_grant".equals(errorResponse.error())
                            ? new AuthState.PermanentFailure(description)
                            : new AuthState.TransientFailure(description);
        listeners.notify(failure);
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
        executor.schedule(expiryDelay, () -> refreshAccessToken(currentToken.refreshToken()));
    }
}
