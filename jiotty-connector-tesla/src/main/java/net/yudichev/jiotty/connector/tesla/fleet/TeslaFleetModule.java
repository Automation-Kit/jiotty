package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.net.SslCustomisation;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.security.OAuth2TokenManagerModule;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class TeslaFleetModule extends BaseLifecycleComponentModule implements ExposedKeyModule<TeslaFleet> {
    private static final String BASE_API_NAME = "TeslaFleet";
    private static final String OFFLINE_ACCESS_SCOPE = "offline_access";

    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec;
    private final BindingSpec<Set<String>> oauthScopesSpec;
    private final BindingSpec<String> logSubjectIdSpec;
    private final @Nullable BindingSpec<VarStore> varStoreSpec;
    private final boolean localLogin;
    private final Key<TeslaFleet> exposedKey;

    private TeslaFleetModule(BindingSpec<String> clientIdSpec,
                             BindingSpec<String> clientSecretSpec,
                             BindingSpec<String> baseUrlSpec,
                             BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec,
                             BindingSpec<Set<String>> oauthScopesSpec,
                             BindingSpec<String> logSubjectIdSpec,
                             @Nullable BindingSpec<VarStore> varStoreSpec,
                             boolean localLogin,
                             SpecifiedAnnotation specifiedAnnotation) {
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
        this.sslCustomisationSpec = checkNotNull(sslCustomisationSpec);
        this.oauthScopesSpec = checkNotNull(oauthScopesSpec);
        this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
        this.varStoreSpec = varStoreSpec;
        this.localLogin = localLogin;
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    /// The space-separated OAuth2 scope string. Always carries {@value #OFFLINE_ACCESS_SCOPE}, which is what makes the token endpoint return a refresh token,
    /// so a caller that omits it from its requested scopes still gets a credential that survives the first access-token expiry.
    @VisibleForTesting
    static String scope(Set<String> scopeSet) {
        String joined = String.join(" ", scopeSet);
        return scopeSet.contains(OFFLINE_ACCESS_SCOPE) ? joined : joined + ' ' + OFFLINE_ACCESS_SCOPE;
    }

    @Override
    public Key<TeslaFleet> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        var tokenManagerModuleBuilder = OAuth2TokenManagerModule
                .builder()
                .setClientId(clientIdSpec)
                .withClientSecret(clientSecretSpec)
                .setApiName(literally(BASE_API_NAME))
                .withLogSubjectId(logSubjectIdSpec)
                .setTokenUrl(literally("https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"))
                .setScope(oauthScopesSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, TeslaFleetModule::scope))
                .withAnnotation(forAnnotation(TeslaFleetImpl.Dependency.class));
        if (varStoreSpec != null) {
            tokenManagerModuleBuilder.withVarStore(varStoreSpec);
        }
        if (localLogin) {
            tokenManagerModuleBuilder.withLoginUrl(literally("https://auth.tesla.com/oauth2/v3/authorize"))
                                     .withFixedCallbackHttpPort(literally(Optional.of(53904)));
        }
        installLifecycleComponentModule(tokenManagerModuleBuilder.build());
        baseUrlSpec.bind(String.class).annotatedWith(TeslaFleetImpl.BaseUrl.class).installedBy(this::installLifecycleComponentModule);
        sslCustomisationSpec.bind(new TypeLiteral<>() {}).annotatedWith(TeslaFleetImpl.Dependency.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(TeslaFleetImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<TeslaFleet, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<String> baseUrlSpec = literally(TeslaHttp.AUDIENCE + "/api/1");
        private BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec = literally(Optional.empty());
        private BindingSpec<Set<String>> oauthScopesSpec = literally(ImmutableSet.of(OFFLINE_ACCESS_SCOPE));
        private BindingSpec<String> logSubjectIdSpec = literally("");
        private BindingSpec<VarStore> varStoreSpec;
        private boolean localLogin;

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        public Builder setClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = checkNotNull(clientSecretSpec);
            return this;
        }

        public Builder withBaseUrl(BindingSpec<String> baseUrlSpec) {
            this.baseUrlSpec = checkNotNull(baseUrlSpec);
            return this;
        }

        public Builder withSslCustomisation(BindingSpec<SslCustomisation> sslCustomisation) {
            sslCustomisationSpec = sslCustomisation.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        public Builder withOauthScopes(BindingSpec<Set<String>> oauthScopesSpec) {
            this.oauthScopesSpec = checkNotNull(oauthScopesSpec);
            return this;
        }

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        /// A GDPR-safe subject id (e.g. the internal user id) used to tag this instance's token-manager logs and its executor thread name, so concurrent
        /// per-user instances stay distinguishable in a shared log and in the executor metrics. Defaults to empty (single-instance use).
        public Builder withLogSubjectId(BindingSpec<String> logSubjectIdSpec) {
            this.logSubjectIdSpec = checkNotNull(logSubjectIdSpec);
            return this;
        }

        /// installs a local login redirect server that listens on `http://localhost:<port>/callback`
        public Builder withLocalLogin(boolean localLogin) {
            this.localLogin = localLogin;
            return this;
        }

        @Override
        public ExposedKeyModule<TeslaFleet> build() {
            return new TeslaFleetModule(clientIdSpec,
                                        clientSecretSpec,
                                        baseUrlSpec,
                                        sslCustomisationSpec,
                                        oauthScopesSpec,
                                        logSubjectIdSpec,
                                        varStoreSpec,
                                        localLogin,
                                        specifiedAnnotation());
        }
    }
}
