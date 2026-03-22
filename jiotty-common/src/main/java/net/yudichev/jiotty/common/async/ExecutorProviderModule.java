package net.yudichev.jiotty.common.async;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;

public final class ExecutorProviderModule extends BaseLifecycleComponentModule implements ExposedKeyModule<SchedulingExecutor> {
    private final BindingSpec<String> threadNameSpec;
    private final Key<SchedulingExecutor> exposedKey;

    private ExecutorProviderModule(BindingSpec<String> threadNameSpec, SpecifiedAnnotation specifiedAnnotation) {
        this.threadNameSpec = checkNotNull(threadNameSpec);
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
        bind(exposedKey).toProvider(registerLifecycleComponent(ExecutorProvider.class));
        expose(exposedKey);
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<SchedulingExecutor>>, HasWithAnnotation {
        private BindingSpec<String> threadNameSpec;
        private SpecifiedAnnotation specifiedAnnotation;

        private Builder() {
        }

        public Builder setThreadName(BindingSpec<String> threadNameSpec) {
            this.threadNameSpec = checkNotNull(threadNameSpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExecutorProviderModule build() {
            return new ExecutorProviderModule(threadNameSpec, specifiedAnnotation);
        }
    }
}
