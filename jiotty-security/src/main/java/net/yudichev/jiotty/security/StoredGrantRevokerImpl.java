package net.yudichev.jiotty.security;

import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getRootCause;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdown;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.RevocationUrl;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

public class StoredGrantRevokerImpl extends BaseLifecycleComponent implements StoredGrantRevoker {
    /// Short enough that a caller waiting on the answer — a disconnect, an erasure step — is not held up for long; a revocation that runs out keeps its token
    /// for that caller's own retry.
    @VisibleForTesting
    static final Duration RETRY_MAX_ELAPSED_TIME = Duration.ofMinutes(2);
    private static final Logger logger = LogManager.getLogger(StoredGrantRevokerImpl.class);

    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final String apiName;
    /// The key of the one token this revoker ends the grant of, or `null` when it ends the grant behind every token of its API and scope, whatever the client.
    private final @Nullable String varStoreKey;
    private final String varStoreKeyPrefix;
    private final String varStoreKeySuffix;
    private final GrantRevocation.Method method;

    private SchedulingExecutor executor;
    private OkHttpClient httpClient;
    private GrantRevocation grantRevocation;

    /// @param clientId the client whose token is revoked, or empty to revoke the token of every client of the API and scope, which needs `revocationUrl`
    /// @param tokenUrl the token endpoint, which a revocation without `revocationUrl` refreshes at and so needs
    @Inject
    public StoredGrantRevokerImpl(ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  @ClientID Optional<String> clientId,
                                  @ClientSecret Optional<String> clientSecret,
                                  @ApiName String apiName,
                                  @TokenUrl Optional<String> tokenUrl,
                                  @RevocationUrl Optional<String> revocationUrl,
                                  @Scope String scope) {
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.apiName = checkNotNull(apiName);
        checkNotNull(clientSecret);
        checkNotNull(scope);
        checkArgument(revocationUrl.isPresent() || (clientId.isPresent() && tokenUrl.isPresent()),
                      "%s: a revoker without a revocation URL needs a client id and a token URL",
                      apiName);
        varStoreKey = clientId.map(id -> OAuth2TokenManagerImpl.varStoreKey(apiName, id, scope)).orElse(null);
        varStoreKeyPrefix = OAuth2TokenManagerImpl.varStoreKeyPrefix(apiName);
        varStoreKeySuffix = OAuth2TokenManagerImpl.varStoreKeySuffix(scope);
        method = revocationUrl.<GrantRevocation.Method>map(GrantRevocation.Method.Rfc7009::new)
                              .orElseGet(() -> new GrantRevocation.Method.Rotation(tokenUrl.orElseThrow(), clientId.orElseThrow(), clientSecret));
    }

    /// Creates the HTTP client the revocations are sent with. Overridden in tests to inject a deterministic fake.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient();
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(apiName + "-grant-revoker");
        httpClient = createHttpClient();
        grantRevocation = new GrantRevocation(apiName,
                                              method,
                                              httpClient,
                                              executor,
                                              outcome -> executor.tryExecute("revocationOutcome", () -> ifNotStopped(outcome)),
                                              () -> OAuth2TokenManagerImpl.retryBackOff(currentDateTimeProvider, RETRY_MAX_ELAPSED_TIME));
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, () -> shutdown(httpClient), executor);
    }

    @Override
    public CompletableFuture<Result> revoke(VarStore varStore) {
        checkNotNull(varStore);
        return whenNotLifecycling(() -> isStartedPlain()
                                        ? executor.submit(() -> whenStartedAndNotLifecycling(() -> revokeOnExecutor(varStore))).thenCompose(Function.identity())
                                        : new CompletableFuture<>());
    }

    /// Runs on [#executor], as does the completion of [GrantRevocation#revoke], which hands every outcome to it.
    private CompletableFuture<Result> revokeOnExecutor(VarStore varStore) {
        return varStoreKey == null ? revokeEveryClient(varStore) : revokeStored(varStore, varStoreKey);
    }

    /// Ends the grants behind every token of this API and scope side by side, and reports the most urgent of their outcomes.
    private CompletableFuture<Result> revokeEveryClient(VarStore varStore) {
        return varStore.keysStartingWith(varStoreKeyPrefix)
                       .stream()
                       .filter(key -> key.endsWith(varStoreKeySuffix))
                       .map(key -> revokeStored(varStore, key))
                       .collect(toFutureOfList())
                       .thenApply(StoredGrantRevokerImpl::mostPressing);
    }

    private static Result mostPressing(List<Result> results) {
        Result mostPressing = Result.NOTHING_STORED;
        for (Result result : results) {
            if (urgency(result) > urgency(mostPressing)) {
                mostPressing = result;
            }
        }
        return mostPressing;
    }

    /// Ranks an outcome by what it asks of the caller: a token kept for a later call comes first, then a grant left in place.
    private static int urgency(Result result) {
        return switch (result) {
            case NOTHING_STORED -> 0;
            case ENDED -> 1;
            case REFUSED -> 2;
            case UNREACHABLE -> 3;
        };
    }

    /// Deletes a stored token that cannot be read before failing, since reading it again would fail again.
    private CompletableFuture<Result> revokeStored(VarStore varStore, String key) {
        Optional<OauthAccessToken> storedToken;
        try {
            storedToken = varStore.readValueEncrypted(OauthAccessToken.class, key);
        } catch (RuntimeException e) {
            varStore.clearValue(key);
            logger.info("[{}] stored token unreadable ({}), so deleted", apiName, getRootCause(e).getClass().getSimpleName());
            throw e;
        }
        return storedToken.map(token -> grantRevocation.revoke(token.refreshToken())
                                                       .thenApply(result -> deleteUnlessKept(varStore, key, token.refreshToken(), result)))
                          .orElseGet(() -> completedFuture(Result.NOTHING_STORED));
    }

    /// Deletes the stored token unless the authorisation server could not be asked about it, or a token stored since has replaced it.
    private Result deleteUnlessKept(VarStore varStore, String key, String revokedRefreshToken, Result result) {
        if (result == Result.UNREACHABLE) {
            logger.info("[{}] stored token kept for a later revocation", apiName);
        } else if (varStore.readValueEncrypted(OauthAccessToken.class, key)
                           .map(OauthAccessToken::refreshToken)
                           .filter(revokedRefreshToken::equals)
                           .isPresent()) {
            varStore.clearValue(key);
            logger.info("[{}] stored token deleted", apiName);
        } else {
            logger.info("[{}] stored token changed since it was read, so left in place", apiName);
        }
        return result;
    }
}
