package net.yudichev.jiotty.common.async.backoff;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/// Passes each operation straight through while recording its name, so tests can assert which call paths are wrapped in retries.
public final class RecordingRetryableOperationExecutor implements RetryableOperationExecutor {
    private final List<String> operationNames = new ArrayList<>();

    @Override
    public <T> CompletableFuture<T> withBackOffAndRetry(String operationName,
                                                        Supplier<? extends CompletableFuture<T>> action,
                                                        BiConsumer<Long, Throwable> backoffEventConsumer) {
        operationNames.add(operationName);
        return action.get();
    }

    /// The name of each executed operation, oldest first.
    public List<String> operationNames() {
        return ImmutableList.copyOf(operationNames);
    }
}
