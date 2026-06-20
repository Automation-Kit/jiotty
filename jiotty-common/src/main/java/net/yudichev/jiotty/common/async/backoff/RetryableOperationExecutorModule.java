package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forNoAnnotation;

public final class RetryableOperationExecutorModule extends BaseLifecycleComponentModule implements ExposedKeyModule<RetryableOperationExecutor> {
    private final BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final Key<RetryableOperationExecutor> exposedKey;

    private RetryableOperationExecutorModule(BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec,
                                             BindingSpec<SchedulingExecutor> executorSpec,
                                             SpecifiedAnnotation specifiedAnnotation) {
        this.backingOffExceptionHandlerSpec = checkNotNull(backingOffExceptionHandlerSpec);
        this.executorSpec = checkNotNull(executorSpec);
        exposedKey = specifiedAnnotation.specify(RetryableOperationExecutor.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<RetryableOperationExecutor> getExposedKey() {
        return exposedKey;
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

    public static final class Builder implements TypedBuilder<ExposedKeyModule<RetryableOperationExecutor>>, HasWithAnnotation {
        private BindingSpec<BackingOffExceptionHandler> backingOffExceptionHandlerSpec;
        private BindingSpec<SchedulingExecutor> executorSpec =
                exposedBy(ExecutorProviderModule.builder()
                                                .setThreadName(literally("retryable-executor"))
                                                .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                .build());
        private SpecifiedAnnotation specifiedAnnotation = forNoAnnotation();

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
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<RetryableOperationExecutor> build() {
            return new RetryableOperationExecutorModule(backingOffExceptionHandlerSpec, executorSpec, specifiedAnnotation);
        }
    }
}
