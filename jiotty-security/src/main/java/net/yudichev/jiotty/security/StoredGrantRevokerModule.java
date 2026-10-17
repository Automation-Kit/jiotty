package net.yudichev.jiotty.security;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

/// Publishes a [StoredGrantRevoker] for the tokens [OAuth2TokenManager]s built with the same API name and scope keep: those of one client, or of every client
/// when no client id is given.
public final class StoredGrantRevokerModule extends BaseExposedKeyModule<StoredGrantRevoker> {
    private final BindingSpec<String> apiNameSpec;
    private final BindingSpec<String> scopeSpec;
    private final BindingSpec<Optional<String>> clientIdSpec;
    private final BindingSpec<Optional<String>> clientSecretSpec;
    private final BindingSpec<Optional<String>> tokenUrlSpec;
    private final BindingSpec<Optional<String>> revocationUrlSpec;

    private StoredGrantRevokerModule(BindingSpec<String> apiNameSpec,
                                     BindingSpec<String> scopeSpec,
                                     BindingSpec<Optional<String>> clientIdSpec,
                                     BindingSpec<Optional<String>> clientSecretSpec,
                                     BindingSpec<Optional<String>> tokenUrlSpec,
                                     BindingSpec<Optional<String>> revocationUrlSpec,
                                     SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.apiNameSpec = checkNotNull(apiNameSpec);
        this.scopeSpec = checkNotNull(scopeSpec);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
        this.revocationUrlSpec = checkNotNull(revocationUrlSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        OAuth2ClientBindings.bind(this::installLifecycleComponentModule, clientSecretSpec, apiNameSpec, revocationUrlSpec, scopeSpec);
        clientIdSpec.bind(new TypeLiteral<>() {}).annotatedWith(ClientID.class).installedBy(this::installLifecycleComponentModule);
        tokenUrlSpec.bind(new TypeLiteral<>() {}).annotatedWith(TokenUrl.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(StoredGrantRevokerImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<StoredGrantRevoker, Builder> {
        private BindingSpec<String> apiNameSpec;
        private BindingSpec<String> scopeSpec;
        private BindingSpec<Optional<String>> clientIdSpec = literally(Optional.empty());
        private BindingSpec<Optional<String>> clientSecretSpec = literally(Optional.empty());
        private BindingSpec<Optional<String>> tokenUrlSpec = literally(Optional.empty());
        private BindingSpec<Optional<String>> revocationUrlSpec = literally(Optional.empty());

        /// The API name the token manager was given, which keys the stored token.
        public Builder setApiName(BindingSpec<String> apiNameSpec) {
            this.apiNameSpec = checkNotNull(apiNameSpec);
            return this;
        }

        /// The scope the token manager was given, which keys the stored token.
        public Builder setScope(BindingSpec<String> scopeSpec) {
            this.scopeSpec = checkNotNull(scopeSpec);
            return this;
        }

        /// The client whose token is revoked. Omit this to revoke the token of every client, which needs [#withRevocationUrl].
        public Builder withClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = clientIdSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        /// Sets the client secret for a confidential client. Omit this for a public client.
        public Builder withClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = clientSecretSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        /// The token endpoint, which a revoker without [#withRevocationUrl] refreshes the token at and so needs.
        public Builder withTokenUrl(BindingSpec<String> tokenUrlSpec) {
            this.tokenUrlSpec = tokenUrlSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
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
            return new StoredGrantRevokerModule(apiNameSpec,
                                                scopeSpec,
                                                clientIdSpec,
                                                clientSecretSpec,
                                                tokenUrlSpec,
                                                revocationUrlSpec,
                                                specifiedAnnotation());
        }
    }
}
