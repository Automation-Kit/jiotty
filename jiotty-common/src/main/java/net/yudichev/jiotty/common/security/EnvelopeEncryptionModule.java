package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;

import static com.google.common.base.Preconditions.checkNotNull;

/// Wires an [EnvelopeEncryption] over a symmetric master key held in the keystore under `masterKeyAlias`. Install it wherever a component needs to encrypt
/// values at rest; each installation reads the same key and holds no state beyond it, so several are harmless.
public final class EnvelopeEncryptionModule extends BaseExposedKeyModule<EnvelopeEncryption> {
    private final BindingSpec<String> masterKeyAliasSpec;
    private final BindingSpec<KeyStoreAccess> keyStoreAccessSpec;

    private EnvelopeEncryptionModule(BindingSpec<String> masterKeyAliasSpec,
                                     BindingSpec<KeyStoreAccess> keyStoreAccessSpec,
                                     SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.masterKeyAliasSpec = checkNotNull(masterKeyAliasSpec, "masterKeyAliasSpec");
        this.keyStoreAccessSpec = checkNotNull(keyStoreAccessSpec, "keyStoreAccessSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        masterKeyAliasSpec.bind(String.class)
                          .annotatedWith(EnvelopeEncryptionImpl.MasterKeyAlias.class)
                          .installedBy(this::installLifecycleComponentModule);
        keyStoreAccessSpec.bind(KeyStoreAccess.class)
                          .annotatedWith(EnvelopeEncryptionImpl.Dependency.class)
                          .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(EnvelopeEncryptionImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<EnvelopeEncryption, Builder> {
        private BindingSpec<String> masterKeyAliasSpec;
        private BindingSpec<KeyStoreAccess> keyStoreAccessSpec;

        private Builder() {
        }

        public Builder setMasterKeyAlias(BindingSpec<String> masterKeyAliasSpec) {
            this.masterKeyAliasSpec = checkNotNull(masterKeyAliasSpec, "masterKeyAliasSpec");
            return this;
        }

        public Builder setKeyStoreAccess(BindingSpec<KeyStoreAccess> keyStoreAccessSpec) {
            this.keyStoreAccessSpec = checkNotNull(keyStoreAccessSpec, "keyStoreAccessSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<EnvelopeEncryption> build() {
            return new EnvelopeEncryptionModule(masterKeyAliasSpec, keyStoreAccessSpec, specifiedAnnotation());
        }
    }
}
