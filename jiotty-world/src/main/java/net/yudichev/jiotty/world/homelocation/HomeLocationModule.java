package net.yudichev.jiotty.world.homelocation;

import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class HomeLocationModule extends BaseExposedKeyModule<HomeLocationService> {
    private final BindingSpec<SchedulingExecutor> executorSpec;

    private HomeLocationModule(BindingSpec<SchedulingExecutor> executorSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
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
