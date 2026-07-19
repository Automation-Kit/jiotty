package net.yudichev.jiotty.common.async;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

public final class ExecutorFactoryImpl implements ExecutorFactory {
    private final ListenerBackedTaskExceptionHandlerRegistry taskExceptionHandlerRegistry;
    /// Absent (so executors are created unmetered) in deployments that install no metrics module; [ExecutorModule]'s optional binding resolves the app-global
    /// registry across the enclosing private module when one is bound.
    @Nullable
    private final MeterRegistry meterRegistry;

    /// Convenience for non-Guice callers (mainly tests): a fresh exception-handler registry and no metrics.
    public ExecutorFactoryImpl() {
        this(new ListenerBackedTaskExceptionHandlerRegistry(), Optional.empty());
    }

    @Inject
    public ExecutorFactoryImpl(ListenerBackedTaskExceptionHandlerRegistry taskExceptionHandlerRegistry, Optional<MeterRegistry> meterRegistry) {
        this.taskExceptionHandlerRegistry = checkNotNull(taskExceptionHandlerRegistry);
        this.meterRegistry = meterRegistry.orElse(null);
    }

    @Override
    public SchedulingExecutor createSingleThreadedSchedulingExecutor(String name, String family, int maxQueueSize) {
        return new SingleThreadedSchedulingExecutor(name, family, maxQueueSize, taskExceptionHandlerRegistry, meterRegistry);
    }
}
