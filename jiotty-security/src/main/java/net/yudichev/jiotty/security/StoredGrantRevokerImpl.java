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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
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
    private final String varStoreKey;
    private final GrantRevocation.Method method;

    private SchedulingExecutor executor;
    private OkHttpClient httpClient;
    private GrantRevocation grantRevocation;

    @Inject
    public StoredGrantRevokerImpl(ExecutorFactory executorFactory,
                                  CurrentDateTimeProvider currentDateTimeProvider,
                                  @ClientID String clientId,
                                  @ClientSecret Optional<String> clientSecret,
                                  @ApiName String apiName,
                                  @TokenUrl String tokenUrl,
                                  @RevocationUrl Optional<String> revocationUrl,
                                  @Scope String scope) {
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.apiName = checkNotNull(apiName);
        varStoreKey = OAuth2TokenManagerImpl.varStoreKey(apiName, checkNotNull(clientId), checkNotNull(scope));
        method = revocationUrl.<GrantRevocation.Method>map(GrantRevocation.Method.Rfc7009::new)
                              .orElseGet(() -> new GrantRevocation.Method.Rotation(tokenUrl, clientId, clientSecret));
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
        return varStore.readValueEncrypted(OauthAccessToken.class, varStoreKey)
                       .map(storedToken -> grantRevocation.revoke(storedToken.refreshToken())
                                                          .thenApply(result -> deleteUnlessKept(varStore, storedToken.refreshToken(), result)))
                       .orElseGet(() -> completedFuture(Result.NOTHING_STORED));
    }

    /// Deletes the stored token unless the authorisation server could not be asked about it, or a token stored since has replaced it.
    private Result deleteUnlessKept(VarStore varStore, String revokedRefreshToken, Result result) {
        if (result == Result.UNREACHABLE) {
            logger.info("[{}] stored token kept for a later revocation", apiName);
        } else if (varStore.readValueEncrypted(OauthAccessToken.class, varStoreKey)
                           .map(OauthAccessToken::refreshToken)
                           .filter(revokedRefreshToken::equals)
                           .isPresent()) {
            varStore.clearValue(varStoreKey);
            logger.info("[{}] stored token deleted", apiName);
        } else {
            logger.info("[{}] stored token changed since it was read, so left in place", apiName);
        }
        return result;
    }
}
