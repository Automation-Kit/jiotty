package net.yudichev.jiotty.user.push;

import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;

public final class PushDeviceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<PushDeviceStore> {
    private final BindingSpec<VarStore> varStoreSpec;

    private PushDeviceModule(BindingSpec<VarStore> varStoreSpec) {
        this.varStoreSpec = checkNotNull(varStoreSpec);
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(PushDeviceStoreImpl.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        registerLifecycleComponent(PushDeviceStoreImpl.class);
        bind(getExposedKey()).to(PushDeviceStoreImpl.class);
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<PushDeviceStore>> {
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<PushDeviceStore> build() {
            return new PushDeviceModule(varStoreSpec);
        }
    }
}
