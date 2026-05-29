package net.yudichev.jiotty.energy;

import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage;
import net.yudichev.jiotty.common.lang.Observable;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.energy.Bindings.ExecutorProvider;

/// Single source of truth for one Octopus account's details (everything except consumption). Acquires the per-credentials [OctopusAccountService] handle once,
/// polls [OctopusAccountService#getAccount] on a fixed interval through a [RetryableOperationExecutor], and publishes the latest outcome as a subscribable
/// [AccountFetchResult]. Auth-state observation is exposed here too, since it is an account-level concern bound to the same credentials.
///
/// The account payload (tariff history, meter points) changes very rarely, so consumers should subscribe to [#accountDetails] and react to changes rather than
/// triggering their own fetches. Consecutive identical fetch failures (same [HumanReadableExceptionMessage]) are deduplicated here: a run of identical failures
/// publishes a single [AccountFetchResult.Failed], so subscribers see one notification per outage regardless of how many retries or polls it spanned. A
/// successful fetch resets the dedup state, so a later identical failure re-notifies.
public final class OctopusAccountContext extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(OctopusAccountContext.class);
    private static final Duration ACCOUNT_POLL_INTERVAL = Duration.ofHours(12);

    private final Provider<SchedulingExecutor> executorProvider;
    private final OctopusEnergy octopusEnergy;
    private final String accountId;
    private final String apiKey;
    private final RetryableOperationExecutor retryExecutor;
    private final ObservableValue<AccountFetchResult> accountResult = ObservableValue.simple(new AccountFetchResult.Loading());

    private SchedulingExecutor executor;
    private OctopusAccountService accountService;
    private @Nullable Closeable pollSchedule;
    /// The [HumanReadableExceptionMessage#humanReadableMessage(Throwable)] of the last published [AccountFetchResult.Failed], cleared on a successful fetch.
    /// Consecutive failures with the same message are suppressed so a single outage produces one notification. Accessed only on the poll executor.
    private @Nullable String lastPublishedFailureMessage;

    @Inject
    public OctopusAccountContext(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                 OctopusEnergy octopusEnergy,
                                 @AccountId String accountId,
                                 @ApiKey String apiKey,
                                 RetryableOperationExecutor retryExecutor) {
        this.executorProvider = checkNotNull(executorProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountId = checkNotNull(accountId);
        this.apiKey = checkNotNull(apiKey);
        this.retryExecutor = checkNotNull(retryExecutor);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        accountService = octopusEnergy.account(accountId, apiKey);
        pollSchedule = executor.scheduleAtFixedRate(Duration.ZERO, ACCOUNT_POLL_INTERVAL, this::poll);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, pollSchedule, accountService);
    }

    /// The latest account-fetch outcome. A new subscriber immediately receives the current value; subsequent polls deliver each change with no conflation.
    public Observable<AccountFetchResult> accountDetails() {
        return whenStartedAndNotLifecycling(() -> accountResult);
    }

    /// Subscribes to the auth-state observable for the credentials this context is bound to. The returned [Closeable] cancels the subscription.
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return whenStartedAndNotLifecycling(() -> accountService.subscribeToAuthState(consumer));
    }

    private void poll() {
        logger.debug("Polling Octopus account");
        // backoffEventConsumer fires on each retry; the final whenComplete fires on retry exhaustion. Both route through publishFailed, which deduplicates
        // identical consecutive messages. Marshal onto this context's executor because RetryableOperationExecutor invokes the consumer on whichever thread
        // completed the failed attempt.
        retryExecutor
                .withBackOffAndRetry("octopusAccount.getAccount",
                                     accountService::getAccount,
                                     (_, throwable) -> executor.submit(() -> publishFailed(throwable)))
                .thenAcceptAsync(this::publishLoaded, executor)
                .whenCompleteAsync((_, throwable) -> {
                    if (throwable != null) {
                        logger.info("Failed to fetch Octopus account after retries", throwable);
                        publishFailed(throwable);
                    }
                }, executor);
    }

    private void publishLoaded(OctopusAccountData account) {
        lastPublishedFailureMessage = null;
        accountResult.accept(new AccountFetchResult.Loaded(account));
    }

    private void publishFailed(Throwable cause) {
        String message = humanReadableMessage(cause);
        if (Objects.equals(message, lastPublishedFailureMessage)) {
            return;
        }
        lastPublishedFailureMessage = message;
        accountResult.accept(new AccountFetchResult.Failed(cause));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ApiKey {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AccountId {
    }
}
