package net.yudichev.jiotty.security;

import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.exposedBy;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.security.Bindings.ApiName;
import static net.yudichev.jiotty.security.Bindings.ClientID;
import static net.yudichev.jiotty.security.Bindings.ClientSecret;
import static net.yudichev.jiotty.security.Bindings.Dependency;
import static net.yudichev.jiotty.security.Bindings.Scope;
import static net.yudichev.jiotty.security.Bindings.TokenUrl;

public final class OAuth2TokenManagerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<OAuth2TokenManager> {
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<Optional<String>> clientSecretSpec;
    private final BindingSpec<String> apiNameSpec;
    private final @Nullable BindingSpec<String> loginUrlSpec;
    private final BindingSpec<String> tokenUrlSpec;
    private final BindingSpec<String> scopeSpec;
    private final BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec;
    private final BindingSpec<Map<String, String>> loginExtraParamsSpec;
    private final BindingSpec<VarStore> varStoreSpec;
    private final BindingSpec<SchedulingExecutor> executorSpec;
    private final Key<OAuth2TokenManager> exposedKey;

    private OAuth2TokenManagerModule(BindingSpec<String> clientIdSpec,
                                     BindingSpec<Optional<String>> clientSecretSpec,
                                     BindingSpec<String> apiNameSpec,
                                     @Nullable BindingSpec<String> loginUrlSpec,
                                     BindingSpec<String> tokenUrlSpec,
                                     BindingSpec<String> scopeSpec,
                                     BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec,
                                     BindingSpec<Map<String, String>> loginExtraParamsSpec,
                                     BindingSpec<VarStore> varStoreSpec,
                                     BindingSpec<SchedulingExecutor> executorSpec,
                                     SpecifiedAnnotation specifiedAnnotation) {
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.apiNameSpec = checkNotNull(apiNameSpec);
        this.loginUrlSpec = loginUrlSpec;
        this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
        this.scopeSpec = checkNotNull(scopeSpec);
        this.fixedCallbackHttpPortSpec = checkNotNull(fixedCallbackHttpPortSpec);
        this.loginExtraParamsSpec = checkNotNull(loginExtraParamsSpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.executorSpec = checkNotNull(executorSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public Key<OAuth2TokenManager> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        clientIdSpec.bind(String.class)
                    .annotatedWith(ClientID.class)
                    .installedBy(this::installLifecycleComponentModule);
        clientSecretSpec.bind(new TypeLiteral<>() {})
                        .annotatedWith(ClientSecret.class)
                        .installedBy(this::installLifecycleComponentModule);
        apiNameSpec.bind(String.class)
                   .annotatedWith(ApiName.class)
                   .installedBy(this::installLifecycleComponentModule);
        tokenUrlSpec.bind(String.class)
                    .annotatedWith(TokenUrl.class)
                    .installedBy(this::installLifecycleComponentModule);
        scopeSpec.bind(String.class)
                 .annotatedWith(Scope.class)
                 .installedBy(this::installLifecycleComponentModule);
        varStoreSpec.bind(new TypeLiteral<>() {})
                    .annotatedWith(Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        executorSpec.bind(SchedulingExecutor.class)
                    .annotatedWith(Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        if (loginUrlSpec != null) {
            loginUrlSpec.bind(String.class)
                        .annotatedWith(LocalLoginOAuth2TokenManager.LoginUrl.class)
                        .installedBy(this::installLifecycleComponentModule);
            fixedCallbackHttpPortSpec.bind(new TypeLiteral<>() {})
                                     .annotatedWith(LocalLoginOAuth2TokenManager.FixedCallbackHttpPort.class)
                                     .installedBy(this::installLifecycleComponentModule);
            loginExtraParamsSpec.bind(new TypeLiteral<>() {})
                                .annotatedWith(LocalLoginOAuth2TokenManager.ExtraLoginParams.class)
                                .installedBy(this::installLifecycleComponentModule);
            bind(exposedKey).to(registerLifecycleComponent(LocalLoginOAuth2TokenManager.class));
        } else {
            bind(exposedKey).to(registerLifecycleComponent(OAuth2TokenManagerImpl.class));
        }

        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<OAuth2TokenManager, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<Optional<String>> clientSecretSpec = BindingSpec.literally(Optional.empty());
        private BindingSpec<String> apiNameSpec;
        private BindingSpec<String> loginUrlSpec;
        private BindingSpec<String> tokenUrlSpec;
        private BindingSpec<String> scopeSpec;
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);
        /// The thread name follows the injected API name, so the default executor is distinguishable per API — and per user, where the API name carries a
        /// subject id.
        private BindingSpec<SchedulingExecutor> executorSpec =
                exposedBy(ExecutorProviderModule.builder()
                                                .setThreadName(BindingSpec.<String>annotatedWith(ApiName.class)
                                                                          .map(new TypeToken<>() {}, new TypeToken<>() {}, apiName -> apiName + "-oauth2"))
                                                .withFamily(literally("oauth2"))
                                                .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                .build());
        private BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec = BindingSpec.literally(Optional.empty());
        private BindingSpec<Map<String, String>> loginExtraParamsSpec = BindingSpec.literally(Map.of());

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = clientIdSpec;
            return this;
        }

        public Builder setApiName(BindingSpec<String> apiNameSpec) {
            this.apiNameSpec = checkNotNull(apiNameSpec);
            return this;
        }

        /// If this is specified, then support for local HTTP auth login is enabled, which is suitable for local unsecured environments.
        public Builder withLoginUrl(BindingSpec<String> loginUrlSpec) {
            this.loginUrlSpec = checkNotNull(loginUrlSpec);
            return this;
        }

        public Builder setTokenUrl(BindingSpec<String> tokenUrlSpec) {
            this.tokenUrlSpec = checkNotNull(tokenUrlSpec);
            return this;
        }

        public Builder setScope(BindingSpec<String> scopeSpec) {
            this.scopeSpec = checkNotNull(scopeSpec);
            return this;
        }

        /// Sets the client secret for a confidential client. Omit this for a public client that authenticates via PKCE instead.
        public Builder withClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = clientSecretSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        /// Only makes sense if [#withLoginUrl] is also called.
        public Builder withFixedCallbackHttpPort(BindingSpec<Optional<Integer>> fixedCallbackHttpPortSpec) {
            this.fixedCallbackHttpPortSpec = checkNotNull(fixedCallbackHttpPortSpec);
            return this;
        }

        /// Extra query parameters appended to the local-login authorization URL (e.g. `access_type=offline`, `prompt=consent` for Google). Only makes sense if
        /// [#withLoginUrl] is also called.
        public Builder withLoginExtraParams(BindingSpec<Map<String, String>> loginExtraParamsSpec) {
            this.loginExtraParamsSpec = checkNotNull(loginExtraParamsSpec);
            return this;
        }

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        /// Runs token refresh and retry scheduling on the specified executor. If not specified, uses its own dedicated thread.
        public Builder withExecutor(BindingSpec<SchedulingExecutor> executorSpec) {
            this.executorSpec = checkNotNull(executorSpec);
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
                                                loginExtraParamsSpec,
                                                varStoreSpec,
                                                executorSpec,
                                                specifiedAnnotation());
        }
    }
}
