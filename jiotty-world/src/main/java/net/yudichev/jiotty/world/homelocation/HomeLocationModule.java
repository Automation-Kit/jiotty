package net.yudichev.jiotty.world.homelocation;

import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class HomeLocationModule extends BaseLifecycleComponentModule implements ExposedKeyModule<HomeLocationService> {
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final Key<HomeLocationService> exposedKey;

    private HomeLocationModule(BindingSpec<SchedulingExecutor> executorSpec, SpecifiedAnnotation specifiedAnnotation) {
        this.executorSpec = checkNotNull(executorSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<HomeLocationService> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        executorSpec.bind(SchedulingExecutor.class)
                    .annotatedWith(HomeLocationServiceImpl.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(HomeLocationServiceImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<HomeLocationService, Builder> {
        private BindingSpec<SchedulingExecutor> executorSpec = exposedBy(ExecutorProviderModule.builder()
                                                                                               .setThreadName(literally("HomeLocation"))
                                                                                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                               .build());

        /// Runs the service's work on the specified executor. If not specified, uses its own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public HomeLocationModule build() {
            return new HomeLocationModule(executorSpec, specifiedAnnotation());
        }
    }
}
