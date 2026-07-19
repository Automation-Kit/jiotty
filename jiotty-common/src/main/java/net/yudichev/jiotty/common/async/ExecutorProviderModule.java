package net.yudichev.jiotty.common.async;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class ExecutorProviderModule extends BaseLifecycleComponentModule implements ExposedKeyModule<SchedulingExecutor> {
    private final BindingSpec<String> threadNameSpec;
    private final BindingSpec<String> familySpec;
    private final BindingSpec<Integer> maxQueueSizeSpec;
    private final Key<SchedulingExecutor> exposedKey;

    private ExecutorProviderModule(BindingSpec<String> threadNameSpec,
                                   BindingSpec<String> familySpec,
                                   BindingSpec<Integer> maxQueueSizeSpec,
                                   SpecifiedAnnotation specifiedAnnotation) {
        this.threadNameSpec = checkNotNull(threadNameSpec);
        this.familySpec = checkNotNull(familySpec);
        this.maxQueueSizeSpec = checkNotNull(maxQueueSizeSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<SchedulingExecutor> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        threadNameSpec.bind(String.class).annotatedWith(ExecutorProvider.ThreadName.class).installedBy(this::installLifecycleComponentModule);
        familySpec.bind(String.class).annotatedWith(ExecutorProvider.Family.class).installedBy(this::installLifecycleComponentModule);
        maxQueueSizeSpec.bind(Integer.class).annotatedWith(ExecutorProvider.MaxQueueSize.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).toProvider(registerLifecycleComponent(ExecutorProvider.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<SchedulingExecutor, Builder> {
        private BindingSpec<String> threadNameSpec;
        private BindingSpec<String> familySpec;
        private BindingSpec<Integer> maxQueueSizeSpec = literally(ExecutorFactory.DEFAULT_MAX_QUEUE_SIZE);

        private Builder() {
        }

        public Builder setThreadName(BindingSpec<String> threadNameSpec) {
            this.threadNameSpec = checkNotNull(threadNameSpec);
            return this;
        }

        /// Sets the metric `family` tag for the created executor. Defaults to the thread name.
        public Builder withFamily(BindingSpec<String> familySpec) {
            this.familySpec = checkNotNull(familySpec);
            return this;
        }

        /// Bounds the executor's pending immediate-task queue. Defaults to [ExecutorFactory#DEFAULT_MAX_QUEUE_SIZE].
        public Builder withMaxQueueSize(BindingSpec<Integer> maxQueueSizeSpec) {
            this.maxQueueSizeSpec = checkNotNull(maxQueueSizeSpec);
            return this;
        }

        @Override
        public ExecutorProviderModule build() {
            return new ExecutorProviderModule(threadNameSpec, familySpec == null ? threadNameSpec : familySpec, maxQueueSizeSpec, specifiedAnnotation());
        }
    }
}
