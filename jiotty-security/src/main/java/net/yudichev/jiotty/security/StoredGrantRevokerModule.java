package net.yudichev.jiotty.security;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Publishes a [StoredGrantRevoker] for the tokens an [OAuth2TokenManager] built with the same client id, API name and scope keeps.
public final class StoredGrantRevokerModule extends BaseExposedKeyModule<StoredGrantRevoker> {
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<Optional<String>> clientSecretSpec;
    private final BindingSpec<String> apiNameSpec;
    private final BindingSpec<String> tokenUrlSpec;
    private final BindingSpec<Optional<String>> revocationUrlSpec;
    private final BindingSpec<String> scopeSpec;

    private StoredGrantRevokerModule(BindingSpec<String> clientIdSpec,
                                     BindingSpec<Optional<String>> clientSecretSpec,
                                     BindingSpec<String> apiNameSpec,
                                     BindingSpec<String> tokenUrlSpec,
                                     BindingSpec<Optional<String>> revocationUrlSpec,
                                     BindingSpec<String> scopeSpec,
                                     SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.apiNameSpec = checkNotNull(apiNameSpec);
        this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
        this.revocationUrlSpec = checkNotNull(revocationUrlSpec);
        this.scopeSpec = checkNotNull(scopeSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        OAuth2ClientBindings.bind(this::installLifecycleComponentModule,
                                  clientIdSpec,
                                  clientSecretSpec,
                                  apiNameSpec,
                                  tokenUrlSpec,
                                  revocationUrlSpec,
                                  scopeSpec);
        bind(exposedKey).to(registerLifecycleComponent(StoredGrantRevokerImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<StoredGrantRevoker, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<Optional<String>> clientSecretSpec = literally(Optional.empty());
        private BindingSpec<String> apiNameSpec;
        private BindingSpec<String> tokenUrlSpec;
        private BindingSpec<Optional<String>> revocationUrlSpec = literally(Optional.empty());
        private BindingSpec<String> scopeSpec;

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        /// The API name the token manager was given, which keys the stored token.
        public Builder setApiName(BindingSpec<String> apiNameSpec) {
            this.apiNameSpec = checkNotNull(apiNameSpec);
            return this;
        }

        public Builder setTokenUrl(BindingSpec<String> tokenUrlSpec) {
            this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
            return this;
        }

        /// The scope the token manager was given, which keys the stored token.
        public Builder setScope(BindingSpec<String> scopeSpec) {
            this.scopeSpec = checkNotNull(scopeSpec);
            return this;
        }

        /// Sets the client secret for a confidential client. Omit this for a public client.
        public Builder withClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = clientSecretSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        /// The authorisation server's token revocation endpoint (RFC 7009). Without it, a grant is ended by refreshing its token twice and discarding what
        /// comes back, which suits only an authorisation server whose refresh tokens are single-use.
        public Builder withRevocationUrl(BindingSpec<String> revocationUrlSpec) {
            this.revocationUrlSpec = revocationUrlSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        @Override
        public ExposedKeyModule<StoredGrantRevoker> build() {
            return new StoredGrantRevokerModule(clientIdSpec,
                                                clientSecretSpec,
                                                apiNameSpec,
                                                tokenUrlSpec,
                                                revocationUrlSpec,
                                                scopeSpec,
                                                specifiedAnnotation());
        }
    }
}
