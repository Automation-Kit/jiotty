package net.yudichev.jiotty.common.keystore;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class KeyStoreEntryModule extends BaseExposedKeyModule<String> {
    private final BindingSpec<String> aliasSpec;

    private KeyStoreEntryModule(BindingSpec<String> aliasSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.aliasSpec = checkNotNull(aliasSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static BindingSpec<String> keyStoreEntry(String alias) {
        return BindingSpec.exposedBy(builder().setAlias(BindingSpec.literally(alias)).build());
    }

    @Override
    protected void configure() {
        aliasSpec.bind(String.class).annotatedWith(KeyStoreEntryProvider.Alias.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).toProvider(KeyStoreEntryProvider.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<String, Builder> {
        private BindingSpec<String> aliasSpec;

        /// Defaults to a unique annotation: the exposed type is a bare [String], so without one a second entry in the same injector collides with the first.
        private Builder() {
            withAnnotation(forAnnotation(uniqueAnnotation()));
        }

        public Builder setAlias(BindingSpec<String> aliasSpec) {
            this.aliasSpec = checkNotNull(aliasSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<String> build() {
            return new KeyStoreEntryModule(aliasSpec, specifiedAnnotation());
        }
    }
}
