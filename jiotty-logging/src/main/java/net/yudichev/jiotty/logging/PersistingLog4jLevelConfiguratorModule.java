package net.yudichev.jiotty.logging;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class PersistingLog4jLevelConfiguratorModule extends BaseLifecycleComponentModule implements ExposedKeyModule<LoggingLevelConfigurator> {
    private final BindingSpec<VarStore> varStoreSpec;
    private final BindingSpec<String> varStoreKeyPrefixSpec;
    private final Key<LoggingLevelConfigurator> exposedKey;

    private PersistingLog4jLevelConfiguratorModule(SpecifiedAnnotation specifiedAnnotation,
                                                   BindingSpec<VarStore> varStoreSpec,
                                                   BindingSpec<String> varStoreKeyPrefixSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.varStoreKeyPrefixSpec = checkNotNull(varStoreKeyPrefixSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<LoggingLevelConfigurator> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(VarStore.class)
                    .annotatedWith(PersistingLog4jLevelConfigurator.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        varStoreKeyPrefixSpec.bind(String.class)
                             .annotatedWith(PersistingLog4jLevelConfigurator.VarStoreKeyPrefix.class)
                             .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(PersistingLog4jLevelConfigurator.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<LoggingLevelConfigurator, Builder> {
        private BindingSpec<VarStore> varStoreSpec;
        private BindingSpec<String> varStoreKeyPrefixSpec = literally("");

        public Builder setVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        public Builder withVarStoreKeyPrefixSpec(BindingSpec<String> varStoreKeyPrefixSpec) {
            this.varStoreKeyPrefixSpec = checkNotNull(varStoreKeyPrefixSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<LoggingLevelConfigurator> build() {
            return new PersistingLog4jLevelConfiguratorModule(specifiedAnnotation(), varStoreSpec, varStoreKeyPrefixSpec);
        }
    }
}
