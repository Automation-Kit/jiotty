package net.yudichev.jiotty.common.keystore;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// To create the keystore, generate a throwaway entry so the store exists, then delete it:
///
/// ```
/// keytool -genkeypair \
///         -alias init \
///         -keyalg RSA \
///         -keysize 2048 \
///         -dname "CN=init" \
///         -keystore secrets.p12 \
///         -storetype PKCS12 \
///         -storepass ChangeMe123 \
///         -keypass ChangeMe123 \
///         -validity 1
/// keytool -delete \
///         -alias init \
///         -keystore secrets.p12 \
///         -storepass ChangeMe123
/// ```
///
/// To import entries:
///
/// ```
/// keytool -importpass \
///         -alias my-service \
///         -keystore secrets.p12 \
///         -storetype PKCS12 \
///         -storepass ChangeMe123
/// ```
public final class KeyStoreAccessModule extends BaseExposedKeyModule<KeyStoreAccess> {
    private final BindingSpec<Path> pathToKeystoreSpec;
    private final BindingSpec<String> keystorePassSpec;
    private final BindingSpec<String> keystoreTypeSpec;

    private KeyStoreAccessModule(BindingSpec<Path> pathToKeystoreSpec,
                                 BindingSpec<String> keystorePassSpec,
                                 BindingSpec<String> keystoreTypeSpec,
                                 SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.pathToKeystoreSpec = checkNotNull(pathToKeystoreSpec);
        this.keystorePassSpec = checkNotNull(keystorePassSpec);
        this.keystoreTypeSpec = checkNotNull(keystoreTypeSpec);
    }

    @Override
    protected void configure() {
        pathToKeystoreSpec.bind(Path.class).annotatedWith(KeyStoreAccessImpl.PathToKeystore.class).installedBy(this::installLifecycleComponentModule);
        keystorePassSpec.bind(String.class).annotatedWith(KeyStoreAccessImpl.KeyStorePass.class).installedBy(this::installLifecycleComponentModule);
        keystoreTypeSpec.bind(String.class).annotatedWith(KeyStoreAccessImpl.KeyStoreType.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(KeyStoreAccessImpl.class);
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<KeyStoreAccess, Builder> {
        private BindingSpec<Path> pathToKeystoreSpec;
        private BindingSpec<String> keystorePassSpec;
        private BindingSpec<String> keystoreTypeSpec = literally("PKCS12");

        public Builder setPathToKeystore(BindingSpec<Path> pathToKeystoreSpec) {
            this.pathToKeystoreSpec = pathToKeystoreSpec;
            return this;
        }

        public Builder setKeystorePass(BindingSpec<String> keystorePassSpec) {
            this.keystorePassSpec = keystorePassSpec;
            return this;
        }

        public Builder withKeystoreType(BindingSpec<String> keystoreTypeSpec) {
            this.keystoreTypeSpec = keystoreTypeSpec;
            return this;
        }

        @Override
        public ExposedKeyModule<KeyStoreAccess> build() {
            return new KeyStoreAccessModule(pathToKeystoreSpec, keystorePassSpec, keystoreTypeSpec, specifiedAnnotation());
        }
    }
}
