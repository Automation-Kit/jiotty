package net.yudichev.jiotty.security;

import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.backoff.BackOff;
import net.yudichev.jiotty.security.StoredGrantRevoker.Result;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getRootCause;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isClientError;
import static net.yudichev.jiotty.common.rest.HttpStatuses.isServerError;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.CREDENTIAL_DEAD_ERRORS;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.REFRESH_TOKEN;
import static net.yudichev.jiotty.security.OAuth2TokenManagerImpl.refreshTokenGrant;

/// Ends a grant at its authorisation server, given the grant's refresh token, retrying a transient failure until the retry budget is spent; an answer is read
/// only as far as the outcome needs, so no token reaches a log line.
final class GrantRevocation {
    /// How many refresh-token grants a [Method.Rotation] sends. The second consumes the refresh token the first brought back, which ends the grace period an
    /// authorisation server gives the stored token once a successor is issued, rather than leaving it usable until that period runs out.
    @VisibleForTesting
    static final int ROTATIONS = 2;
    /// The error Google's revocation endpoint answers with HTTP 400 for a token it no longer recognises, which leaves no grant to revoke.
    @VisibleForTesting
    static final String INVALID_TOKEN_ERROR = "invalid_token";
    /// The RFC 7009 revocation request parameter carrying the token to revoke.
    @VisibleForTesting
    static final String TOKEN_PARAM = "token";
    /// The RFC 7009 revocation request parameter naming the kind of token sent.
    @VisibleForTesting
    static final String TOKEN_TYPE_HINT_PARAM = "token_type_hint";

    private static final Logger logger = LogManager.getLogger(GrantRevocation.class);

    private final String apiName;
    private final Method method;
    private final OkHttpClient httpClient;
    private final SchedulingExecutor executor;
    private final Consumer<Runnable> outcomeDispatcher;
    private final Supplier<BackOff> backOffFactory;

    /// @param outcomeDispatcher runs an answer's handling on `executor`, or drops it once the owner has stopped
    public GrantRevocation(String apiName,
                           Method method,
                           OkHttpClient httpClient,
                           SchedulingExecutor executor,
                           Consumer<Runnable> outcomeDispatcher,
                           Supplier<BackOff> backOffFactory) {
        this.apiName = checkNotNull(apiName);
        this.method = checkNotNull(method);
        this.httpClient = checkNotNull(httpClient);
        this.executor = checkNotNull(executor);
        this.outcomeDispatcher = checkNotNull(outcomeDispatcher);
        this.backOffFactory = checkNotNull(backOffFactory);
    }

    /// @return [Result#ENDED], [Result#REFUSED] or [Result#UNREACHABLE], completed on the executor; never completes once the owner has stopped
    public CompletableFuture<Result> revoke(String refreshToken) {
        var revocation = new CompletableFuture<Result>();
        send(refreshToken, 1, backOffFactory.get(), revocation);
        return revocation;
    }

    /// @param use which refresh-token grant of a [Method.Rotation] this is, from 1; always 1 for [Method.Rfc7009]
    private void send(String refreshToken, int use, BackOff backOff, CompletableFuture<Result> revocation) {
        boolean needsNextToken = method instanceof Method.Rotation && use < ROTATIONS;
        httpClient.newCall(request(refreshToken)).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                outcomeDispatcher.accept(() -> retryOrGiveUp(refreshToken, use, backOff, revocation, e));
            }

            @Override
            public void onResponse(Call call, Response response) {
                Runnable outcome;
                try (ResponseBody body = response.body()) {
                    if (!response.isSuccessful()) {
                        String error = errorCode(body.string());
                        outcome = () -> handleRefusal(refreshToken, use, backOff, revocation, response.code(), error);
                    } else if (needsNextToken) {
                        String nextRefreshToken = nextRefreshToken(body.string());
                        outcome = () -> rotateAgain(nextRefreshToken, use, backOff, revocation);
                    } else {
                        outcome = () -> {
                            logger.info("[{}] grant revoked", apiName);
                            revocation.complete(Result.ENDED);
                        };
                    }
                } catch (RuntimeException | IOException e) {
                    outcome = () -> retryOrGiveUp(refreshToken, use, backOff, revocation, e);
                }
                outcomeDispatcher.accept(outcome);
            }
        });
    }

    private Request request(String refreshToken) {
        return switch (method) {
            case Method.Rfc7009 rfc7009 -> new Request.Builder().url(rfc7009.revocationUrl())
                                                                .post(new FormBody.Builder().add(TOKEN_PARAM, refreshToken)
                                                                                            .add(TOKEN_TYPE_HINT_PARAM, REFRESH_TOKEN)
                                                                                            .build())
                                                                .build();
            case Method.Rotation rotation -> new Request.Builder().url(rotation.tokenUrl())
                                                                  .post(refreshTokenGrant(refreshToken, rotation.clientId(), rotation.clientSecret()))
                                                                  .build();
        };
    }

    /// Sends the next grant of a rotation with the refresh token the previous one brought back. Runs on the executor.
    ///
    /// @param nextRefreshToken the refresh token the previous grant brought back, or `null` when it brought none
    private void rotateAgain(@Nullable String nextRefreshToken, int use, BackOff backOff, CompletableFuture<Result> revocation) {
        if (nextRefreshToken == null) {
            logger.warn("[{}] grant revocation refused: token rotation answered with no refresh token", apiName);
            revocation.complete(Result.REFUSED);
        } else {
            send(nextRefreshToken, use + 1, backOff, revocation);
        }
    }

    /// Ends `revocation` when the grant is already gone or the answer refuses it, and retries a server-side or rate-limit failure. Runs on the executor.
    ///
    /// @param error the OAuth error code the answer carried, or empty when it carried none
    private void handleRefusal(String refreshToken, int use, BackOff backOff, CompletableFuture<Result> revocation, int status, String error) {
        if (isClientError(status) && (INVALID_TOKEN_ERROR.equals(error) || CREDENTIAL_DEAD_ERRORS.contains(error))) {
            logger.info("[{}] grant already ended (HTTP {}, error '{}')", apiName, status, error);
            revocation.complete(Result.ENDED);
        } else if (isServerError(status) || status == TOO_MANY_REQUESTS_429) {
            retryOrGiveUp(refreshToken, use, backOff, revocation, new RuntimeException(apiName + ": grant revocation answered HTTP " + status));
        } else {
            logger.warn("[{}] grant revocation refused with HTTP {}, error '{}'", apiName, status, error);
            revocation.complete(Result.REFUSED);
        }
    }

    /// Sends the same request again after a backoff delay, or ends `revocation` as unreachable once the retry budget is spent. Runs on the executor.
    private void retryOrGiveUp(String refreshToken, int use, BackOff backOff, CompletableFuture<Result> revocation, Throwable failure) {
        long backOffMillis = backOff.nextBackOffMillis();
        if (backOffMillis == BackOff.STOP) {
            logger.warn("[{}] grant revocation failed and the retry budget is spent: {}", apiName, humanReadableMessage(failure));
            revocation.complete(Result.UNREACHABLE);
        } else {
            logger.info("[{}] grant revocation failed ({}); retrying in {}ms", apiName, humanReadableMessage(failure), backOffMillis);
            executor.schedule(Duration.ofMillis(backOffMillis), () -> send(refreshToken, use, backOff, revocation));
        }
    }

    /// @return the OAuth error code `body` carries, or empty when `body` is not an OAuth error response
    private String errorCode(String body) {
        try {
            return Json.parse(body, OauthErrorResponse.class).error();
        } catch (RuntimeException e) {
            // The type alone: a parser's message can quote the body, which can quote the token.
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] grant revocation error response is not an OAuth error: {}", apiName, getRootCause(e).getClass().getSimpleName());
            }
            return "";
        }
    }

    /// @return the refresh token a rotation's token response `body` carries, or `null` when it carries none
    private @Nullable String nextRefreshToken(String body) {
        try {
            return Json.parse(body, OauthAccessTokenResponse.class).refreshToken().orElse(null);
        } catch (RuntimeException e) {
            // The type alone: a parser's message can quote the body, which carries a token.
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] token rotation response is not a token response: {}", apiName, getRootCause(e).getClass().getSimpleName());
            }
            return null;
        }
    }

    /// How a grant is ended.
    public sealed interface Method {
        /// RFC 7009: posts the refresh token to the authorisation server's revocation endpoint.
        record Rfc7009(String revocationUrl) implements Method {
            public Rfc7009 {
                checkNotNull(revocationUrl);
            }
        }

        /// For an authorisation server with no revocation endpoint and single-use refresh tokens: refreshes [#ROTATIONS] times and discards what comes
        /// back, so the stored refresh token is spent and the last one issued is held by no one.
        record Rotation(String tokenUrl, String clientId, Optional<String> clientSecret) implements Method {
            public Rotation {
                checkNotNull(tokenUrl);
                checkNotNull(clientId);
                checkNotNull(clientSecret);
            }
        }
    }
}
