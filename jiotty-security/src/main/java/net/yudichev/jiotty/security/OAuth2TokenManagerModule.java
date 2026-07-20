package net.yudichev.jiotty.security;

import com.google.common.annotations.VisibleForTesting;
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
import static net.yudichev.jiotty.security.Bindings.LogSubjectId;
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
    private final BindingSpec<String> logSubjectIdSpec;
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
                                     BindingSpec<String> logSubjectIdSpec,
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
        this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
        this.executorSpec = checkNotNull(executorSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public Key<OAuth2TokenManager> getExposedKey() {
        return exposedKey;
    }

    /// The executor thread name: `{apiName}-oauth2`, carrying the subject id when one is supplied so concurrent per-user instances stay distinguishable in a
    /// shared log and in the executor metrics. The subject id reaches this name only — [OAuth2TokenManagerImpl] keys its persisted token by the API name, so
    /// folding a per-user value into that name would orphan every stored token.
    @VisibleForTesting
    static String executorThreadName(String apiName, String logSubjectId) {
        return logSubjectId.isBlank() ? apiName + "-oauth2" : apiName + '-' + logSubjectId + "-oauth2";
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
        logSubjectIdSpec.bind(String.class)
                        .annotatedWith(LogSubjectId.class)
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
        private BindingSpec<String> logSubjectIdSpec = literally("");
        /// The thread name combines the API name with the subject id, so the default executor is distinguishable per API and per user. It is built from the
        /// injected bindings rather than the API name alone, because the API name also keys this manager's persisted token and must stay stable.
        private BindingSpec<SchedulingExecutor> executorSpec =
                exposedBy(ExecutorProviderModule.builder()
                                                .setThreadName(BindingSpec.<String>annotatedWith(LogSubjectId.class)
                                                                          .map(new TypeToken<>() {},
                                                                               new TypeToken<>() {},
                                                                               BindingSpec.<String>annotatedWith(ApiName.class)
                                                                                          .map(new TypeToken<>() {},
                                                                                               new TypeToken<>() {},
                                                                                               apiName -> subjectId -> executorThreadName(apiName, subjectId))))
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

        /// A GDPR-safe subject id (e.g. the internal user id) that tags this instance's executor thread name, so concurrent per-user instances stay
        /// distinguishable in a shared log and in the executor metrics. It reaches the thread name only — the API name keys the persisted token, so it stays
        /// out of that. Defaults to empty (single-instance use).
        public Builder withLogSubjectId(BindingSpec<String> logSubjectIdSpec) {
            this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
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
                                                logSubjectIdSpec,
                                                executorSpec,
                                                specifiedAnnotation());
        }
    }
}
