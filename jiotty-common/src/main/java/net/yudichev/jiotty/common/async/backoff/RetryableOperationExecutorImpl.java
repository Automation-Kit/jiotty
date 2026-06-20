package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class RetryableOperationExecutorImpl implements RetryableOperationExecutor {
    private static final Logger logger = LogManager.getLogger(RetryableOperationExecutorImpl.class);

    private final Provider<BackingOffExceptionHandler> exceptionHandlerProvider;
    private final Provider<SchedulingExecutor> executorProvider;

    @Inject
    RetryableOperationExecutorImpl(@Dependency Provider<BackingOffExceptionHandler> exceptionHandlerProvider,
                                   @Dependency Provider<SchedulingExecutor> executorProvider) {
        this.exceptionHandlerProvider = checkNotNull(exceptionHandlerProvider);
        this.executorProvider = checkNotNull(executorProvider);
    }

    @Override
    public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                        Supplier<? extends CompletableFuture<T>> action,
                                                        BiConsumer<Long, Throwable> backoffEventConsumer) {
        return new Retry<>(operationName, action, backoffEventConsumer, exceptionHandlerProvider.get(), executorProvider.get()).start();
    }

    /// A single retryable operation. All bookkeeping (the pending-cancellable, completion handling, retry scheduling) runs on the one retry-executor thread, so
    /// the state needs no synchronisation; completions that may fire on another thread hop back onto the executor before touching it.
    private static final class Retry<T> {
        private final String operationName;
        private final Supplier<? extends CompletableFuture<T>> action;
        private final BiConsumer<Long, Throwable> backoffEventConsumer;
        private final BackingOffExceptionHandler exceptionHandler;
        private final SchedulingExecutor executor;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        /// What is currently cancellable for this operation - the in-flight attempt, or the handle of a scheduled retry; `null` before the first attempt is
        /// submitted. Accessed only on the retry-executor thread.
        private @Nullable Closeable pendingCancellable;

        Retry(String operationName,
              Supplier<? extends CompletableFuture<T>> action,
              BiConsumer<Long, Throwable> backoffEventConsumer,
              BackingOffExceptionHandler exceptionHandler,
              SchedulingExecutor executor) {
            this.operationName = checkNotNull(operationName);
            this.action = checkNotNull(action);
            this.backoffEventConsumer = checkNotNull(backoffEventConsumer);
            this.exceptionHandler = checkNotNull(exceptionHandler);
            this.executor = checkNotNull(executor);
        }

        CompletableFuture<T> start() {
            logger.debug("Executing operation '{}' with retries using handler {}", operationName, exceptionHandler);
            result.whenComplete((_, _) -> {
                if (result.isCancelled()) {
                    executor.execute(() -> closeSafelyIfNotNull(logger, pendingCancellable));
                }
            });
            executor.execute(this::attempt);
            return result;
        }

        private void attempt() {
            if (result.isDone()) {
                return;
            }
            CompletableFuture<? extends T> attemptFuture;
            try {
                attemptFuture = action.get();
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                return;
            }
            pendingCancellable = Closeable.idempotent(() -> attemptFuture.cancel(true));
            // The action's future may complete on any thread; hop back onto the retry executor before touching any bookkeeping.
            attemptFuture.whenComplete((value, exception) -> executor.execute(() -> onAttemptComplete(value, exception)));
        }

        private void onAttemptComplete(@Nullable T value, @Nullable Throwable exception) {
            if (result.isDone()) {
                return;
            }
            if (exception == null) {
                result.complete(value);
                return;
            }
            Optional<Long> backoffDelayMs;
            try {
                backoffDelayMs = exceptionHandler.handle(operationName, exception);
            } catch (RuntimeException e) {
                // backoff gave up (exceeded max elapsed time) - fail with that reason
                result.completeExceptionally(e);
                return;
            }
            if (backoffDelayMs.isEmpty()) {
                result.completeExceptionally(exception);
                return;
            }
            long delayMs = backoffDelayMs.orElseThrow();
            logger.debug("Retrying operation '{}' with backoff {}ms", operationName, delayMs);
            backoffEventConsumer.accept(delayMs, exception);
            pendingCancellable = executor.schedule(Duration.ofMillis(delayMs), this::attempt);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
