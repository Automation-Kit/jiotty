package net.yudichev.jiotty.common.async.backoff;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class RetryableOperationExecutorModule extends BaseExposedKeyModule<RetryableOperationExecutor> {
    private final BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;

    private RetryableOperationExecutorModule(BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec,
                                             BindingSpec<SchedulingExecutor> executorSpec,
                                             SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.backingOffExceptionHandlerSpec = checkNotNull(backingOffExceptionHandlerSpec);
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        backingOffExceptionHandlerSpec.bind(BackingOffExceptionHandler.class)
                                      .annotatedWith(RetryableOperationExecutorImpl.Dependency.class)
                                      .installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class)
                    .annotatedWith(RetryableOperationExecutorImpl.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(RetryableOperationExecutorImpl.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<RetryableOperationExecutor, Builder> {
        private BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec;
        private BindingSpec<SchedulingExecutor> executorSpec =
                exposedBy(ExecutorProviderModule.builder()
                                                .setThreadName(literally("retryable-executor"))
                                                .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                .build());

        public Builder setBackingOffExceptionHandler(BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec) {
            this.backingOffExceptionHandlerSpec = checkNotNull(backingOffExceptionHandlerSpec);
            return this;
        }

        /// Sets the executor used to schedule backoff retries. Defaults to a dedicated single-threaded `retryable-executor`. Prefer an executor that already
        /// exists in the surrounding context (e.g. the owning graph/user executor) so retries reuse a single existing thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<RetryableOperationExecutor> build() {
            return new RetryableOperationExecutorModule(backingOffExceptionHandlerSpec, executorSpec, specifiedAnnotation());
        }
    }
}
