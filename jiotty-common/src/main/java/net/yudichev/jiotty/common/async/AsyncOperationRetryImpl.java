package net.yudichev.jiotty.common.async;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.Either;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.lang.CompletableFutures.thenComplete;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

public class AsyncOperationRetryImpl implements AsyncOperationRetry {
    private static final Logger logger = LogManager.getLogger(AsyncOperationRetryImpl.class);
    private final AsyncOperationFailureHandler backOffHandler;

    @Inject
    public AsyncOperationRetryImpl(AsyncOperationFailureHandler backOffHandler) {
        this.backOffHandler = checkNotNull(backOffHandler);
    }

    @Override
    public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                        Supplier<? extends CompletableFuture<T>> action,
                                                        BiConsumer<? super Long, Runnable> backoffHandler) {
        return action.get()
                     .thenApply(value -> {
                         backOffHandler.reset();
                         return Either.<T, RetryableFailure>left(value);
                     })
                     .exceptionally(exception -> {
                         Optional<Long> backoffDelayMs = backOffHandler.handle(operationName, exception);
                         return Either.right(RetryableFailure.of(exception, backoffDelayMs));
                     })
                     .thenCompose(eitherValueOrRetryableFailure -> eitherValueOrRetryableFailure.map(
                             CompletableFuture::completedFuture,
                             retryableFailure -> retryableFailure.backoffDelayMs()
                                                                 .map(backoffDelayMs -> {
                                                                     logger.info("Retrying operation '{}' with backoff {}ms because: {}",
                                                                                 operationName,
                                                                                 backoffDelayMs,
                                                                                 humanReadableMessage(retryableFailure.exception()));
                                                                     var retryFuture = new CompletableFuture<T>();
                                                                     backoffHandler.accept(
                                                                             backoffDelayMs,
                                                                             () -> withBackOffAndRetry(operationName, action, backoffHandler).whenComplete(
                                                                                     thenComplete(retryFuture)));
                                                                     return retryFuture;
                                                                 })
                                                                 .orElseGet(() -> failure(retryableFailure.exception()))
                     ));
    }
}
