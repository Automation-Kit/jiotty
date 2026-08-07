package net.yudichev.jiotty.user.push;

import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class PushDeviceModule extends BaseExposedKeyModule<PushDeviceStore> {
    private final BindingSpec<VarStore> varStoreSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;

    private PushDeviceModule(BindingSpec<VarStore> varStoreSpec,
                             BindingSpec<SchedulingExecutor> executorSpec,
                             SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.executorSpec = checkNotNull(executorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(PushDeviceStoreImpl.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class)
                    .annotatedWith(PushDeviceStoreImpl.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        registerLifecycleComponent(PushDeviceStoreImpl.class);
        bind(exposedKey).to(PushDeviceStoreImpl.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<PushDeviceStore, Builder> {
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);
        private BindingSpec<SchedulingExecutor> executorSpec = exposedBy(ExecutorProviderModule.builder()
                                                                                               .setThreadName(literally("push-device-store"))
                                                                                               .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                               .build());

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        /// Runs the store's work on the specified executor. If not specified, uses its own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
            return this;
        }

        @Override
        public PushDeviceModule build() {
            return new PushDeviceModule(varStoreSpec, executorSpec, specifiedAnnotation());
        }
    }
}
