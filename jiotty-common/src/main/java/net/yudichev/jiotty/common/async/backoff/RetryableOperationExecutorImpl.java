package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.immutables.value.Value;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

final class RetryableOperationExecutorImpl implements RetryableOperationExecutor {
    private static final Logger logger = LogManager.getLogger(RetryableOperationExecutorImpl.class);
    private final Provider<BackingOffExceptionHandler> exceptionHandlerProvider;

    @Inject
    RetryableOperationExecutorImpl(@Dependency Provider<BackingOffExceptionHandler> exceptionHandlerProvider) {
        this.exceptionHandlerProvider = checkNotNull(exceptionHandlerProvider);
    }

    @Override
    public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                        Supplier<? extends CompletableFuture<T>> action,
                                                        BiConsumer<Long, Throwable> backoffEventConsumer) {
        var exceptionHandler = exceptionHandlerProvider.get();
        logger.debug("Executing operation '{}' with retries using handler {}", operationName, exceptionHandler);
        return doWithBackOffAndRetry(operationName, action, backoffEventConsumer, exceptionHandler);
    }

    private static <T> CompletableFuture<T> doWithBackOffAndRetry(String operationName,
                                                                  Supplier<? extends CompletableFuture<T>> action,
                                                                  BiConsumer<Long, Throwable> backoffEventConsumer,
                                                                  BackingOffExceptionHandler exceptionHandler) {
        return action.get()
                     .thenApply(Either::<T, RetryableFailure>left)
                     .exceptionally(exception -> {
                         Optional<Long> backoffDelayMs = exceptionHandler.handle(operationName, exception);
                         return Either.right(RetryableFailure.of(exception, backoffDelayMs));
                     })
                     .thenCompose(eitherValueOrRetryableFailure -> eitherValueOrRetryableFailure.map(
                             CompletableFuture::completedFuture,
                             retryableFailure -> retryableFailure.backoffDelayMs()
                                                                 .map(backoffDelayMs -> {
                                                                     logger.debug("Retrying operation '{}' with backoff {}ms",
                                                                                  operationName,
                                                                                  retryableFailure.backoffDelayMs());
                                                                     backoffEventConsumer.accept(backoffDelayMs, retryableFailure.exception());
                                                                     return doWithBackOffAndRetry(operationName,
                                                                                                  action,
                                                                                                  backoffEventConsumer,
                                                                                                  exceptionHandler);
                                                                 })
                                                                 .orElseGet(() -> CompletableFutures.failure(retryableFailure.exception()))
                     ));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @Value.Immutable
    @PackagePrivateImmutablesStyle
    interface BaseRetryableFailure {
        @Value.Parameter
        Throwable exception();

        @Value.Parameter
        Optional<Long> backoffDelayMs();
    }
}
