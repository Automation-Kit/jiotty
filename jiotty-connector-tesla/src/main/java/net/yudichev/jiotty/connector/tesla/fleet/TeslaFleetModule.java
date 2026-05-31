package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
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
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec;
    private final BindingSpec<Set<String>> oauthScopesSpec;
    private final @Nullable BindingSpec<VarStore> varStoreSpec;
    private final boolean localLogin;

    private TeslaFleetModule(BindingSpec<String> clientIdSpec,
                             BindingSpec<String> clientSecretSpec,
                             BindingSpec<String> baseUrlSpec,
                             BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec,
                             BindingSpec<Set<String>> oauthScopesSpec,
                             @Nullable BindingSpec<VarStore> varStoreSpec,
                             boolean localLogin) {
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
        this.sslCustomisationSpec = checkNotNull(sslCustomisationSpec);
        this.oauthScopesSpec = checkNotNull(oauthScopesSpec);
        this.varStoreSpec = varStoreSpec;
        this.localLogin = localLogin;
    }

    @Override
    protected void configure() {
        var tokenManagerModuleBuilder = OAuth2TokenManagerModule
                .builder()
                .setClientId(clientIdSpec)
                .setClientSecret(clientSecretSpec)
                .setApiName(literally("TeslaFleet"))
                .setTokenUrl(literally("https://fleet-auth.prd.vn.cloud.tesla.com/oauth2/v3/token"))
                .setScope(oauthScopesSpec.map(new TypeToken<>() {},
                                              new TypeToken<>() {},
                                              scopeSet -> {
                                                  String result = String.join(" ", scopeSet);
                                                  if (!scopeSet.contains("offline_access")) {
                                                      result += " offline_access";
                                                  }
                                                  return result;
                                              }))
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
        bind(getExposedKey()).to(registerLifecycleComponent(TeslaFleetImpl.class));
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<TeslaFleet>> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<String> baseUrlSpec = literally(TeslaFleetImpl.AUDIENCE + "/api/1");
        private BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec = literally(Optional.empty());
        private BindingSpec<Set<String>> oauthScopesSpec = literally(ImmutableSet.of("offline_access"));
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

        /// installs a local login redirect server that listens on `http://localhost:<port>/callback`
        @SuppressWarnings("JavadocLinkAsPlainText")
        public Builder withLocalLogin(boolean localLogin) {
            this.localLogin = localLogin;
            return this;
        }

        @Override
        public ExposedKeyModule<TeslaFleet> build() {
            return new TeslaFleetModule(clientIdSpec, clientSecretSpec, baseUrlSpec, sslCustomisationSpec, oauthScopesSpec, varStoreSpec, localLogin);
        }
    }
}
