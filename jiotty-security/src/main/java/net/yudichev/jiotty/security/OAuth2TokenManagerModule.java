package net.yudichev.jiotty.security;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

public final class OAuth2TokenManagerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<OAuth2TokenManager> {
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;
    private final BindingSpec<String> apiNameSpec;
    private final BindingSpec<String> loginUrlSpec;
    private final BindingSpec<String> tokenUrlSpec;
    private final BindingSpec<String> scopeSpec;
    private final BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec;
    private final Key<OAuth2TokenManager> exposedKey;

    private OAuth2TokenManagerModule(BindingSpec<String> clientIdSpec,
                                     BindingSpec<String> clientSecretSpec,
                                     BindingSpec<String> apiNameSpec,
                                     BindingSpec<String> loginUrlSpec,
                                     BindingSpec<String> tokenUrlSpec,
                                     BindingSpec<String> scopeSpec,
                                     BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec,
                                     SpecifiedAnnotation specifiedAnnotation) {
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.apiNameSpec = checkNotNull(apiNameSpec);
        this.loginUrlSpec = checkNotNull(loginUrlSpec);
        this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
        this.scopeSpec = checkNotNull(scopeSpec);
        this.fixedCallbackHttpPortSpec = checkNotNull(fixedCallbackHttpPortSpec);
        exposedKey = specifiedAnnotation.specify(OAuth2TokenManager.class);
    }

    @Override
    public Key<OAuth2TokenManager> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        clientIdSpec.bind(String.class)
                    .annotatedWith(OAuth2TokenManagerImpl.ClientID.class)
                    .installedBy(this::installLifecycleComponentModule);
        clientSecretSpec.bind(String.class)
                        .annotatedWith(OAuth2TokenManagerImpl.ClientSecret.class)
                        .installedBy(this::installLifecycleComponentModule);
        apiNameSpec.bind(String.class)
                   .annotatedWith(OAuth2TokenManagerImpl.ApiName.class)
                   .installedBy(this::installLifecycleComponentModule);
        loginUrlSpec.bind(String.class)
                    .annotatedWith(OAuth2TokenManagerImpl.LoginUrl.class)
                    .installedBy(this::installLifecycleComponentModule);
        tokenUrlSpec.bind(String.class)
                    .annotatedWith(OAuth2TokenManagerImpl.TokenUrl.class)
                    .installedBy(this::installLifecycleComponentModule);
        scopeSpec.bind(String.class)
                 .annotatedWith(OAuth2TokenManagerImpl.Scope.class)
                 .installedBy(this::installLifecycleComponentModule);
        fixedCallbackHttpPortSpec.bind(new TypeLiteral<>() {})
                                 .annotatedWith(OAuth2TokenManagerImpl.FixedCallbackHttpPort.class)
                                 .installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(OAuth2TokenManagerImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<OAuth2TokenManager>>, HasWithAnnotation {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<String> apiNameSpec;
        private BindingSpec<String> loginUrlSpec;
        private BindingSpec<String> tokenUrlSpec;
        private BindingSpec<String> scopeSpec;
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec = BindingSpec.literally(Optional.empty());

        public Builder setClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = clientSecretSpec;
            return this;
        }

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = clientIdSpec;
            return this;
        }

        public Builder setApiName(BindingSpec<String> apiNameSpec) {
            this.apiNameSpec = checkNotNull(apiNameSpec);
            return this;
        }

        public Builder setLoginUrl(BindingSpec<String> loginUrlSpec) {
            this.loginUrlSpec = checkNotNull(loginUrlSpec);
            return this;
        }

        public Builder setTokenUrlSpec(BindingSpec<String> tokenUrlSpec) {
            this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
            return this;
        }

        public Builder setScope(BindingSpec<String> scopeSpec) {
            this.scopeSpec = checkNotNull(scopeSpec);
            return this;
        }

        public Builder withFixedCallbackHttpPort(BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec) {
            this.fixedCallbackHttpPortSpec = checkNotNull(fixedCallbackHttpPortSpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<OAuth2TokenManager> build() {
            return new OAuth2TokenManagerModule(clientIdSpec,
                                                clientSecretSpec,
                                                apiNameSpec,
                                                loginUrlSpec,
                                                tokenUrlSpec,
                                                scopeSpec,
                                                fixedCallbackHttpPortSpec,
                                                specifiedAnnotation);
        }
    }
}
