package net.yudichev.jiotty.common.async.backoff;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public interface RetryableOperationExecutor {

    RetryableOperationExecutor NO_RETRIES = new RetryableOperationExecutor() {
        @Override
        public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                            Supplier<? extends CompletableFuture<T>> action,
                                                            BiConsumer<Long, Throwable> backoffEventConsumer) {
            return action.get();
        }
    };

    default <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                         Supplier<? extends CompletableFuture<T>> action) {
        return withBackOffAndRetry(operationName, action, (_, _) -> {});

    }

    <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                 Supplier<? extends CompletableFuture<T>> action,
                                                 BiConsumer<Long, Throwable> backoffEventConsumer);

    static RetryableOperationExecutor noRetries() {
        return NO_RETRIES;
    }
}
