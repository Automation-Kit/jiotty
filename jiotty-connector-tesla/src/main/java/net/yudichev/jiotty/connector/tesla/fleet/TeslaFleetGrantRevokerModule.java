package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.security.StoredGrantRevoker;
import net.yudichev.jiotty.security.StoredGrantRevokerModule;

import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

/// Publishes a [StoredGrantRevoker] for the Tesla tokens [TeslaFleetModule] stores, keyed exactly as it keys them, so one instance serves every var store such
/// a module writes to. Tesla offers no revocation endpoint and its refresh tokens are single-use, so a grant is ended by refreshing it twice and discarding the
/// answer.
public final class TeslaFleetGrantRevokerModule extends BaseExposedKeyModule<StoredGrantRevoker> {
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;
    private final BindingSpec<Set<String>> oauthScopesSpec;

    private TeslaFleetGrantRevokerModule(BindingSpec<String> clientIdSpec,
                                         BindingSpec<String> clientSecretSpec,
                                         BindingSpec<Set<String>> oauthScopesSpec,
                                         SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.oauthScopesSpec = checkNotNull(oauthScopesSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        ExposedKeyModule<StoredGrantRevoker> revokerModule =
                StoredGrantRevokerModule.builder()
                                        .setClientId(clientIdSpec)
                                        .withClientSecret(clientSecretSpec)
                                        .setApiName(literally(TeslaFleetModule.BASE_API_NAME))
                                        .setTokenUrl(literally(TeslaHttp.TOKEN_URL))
                                        .setScope(oauthScopesSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, TeslaFleetModule::scope))
                                        .withAnnotation(forAnnotation(uniqueAnnotation()))
                                        .build();
        installLifecycleComponentModule(revokerModule);
        bind(exposedKey).to(revokerModule.getExposedKey());
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<StoredGrantRevoker, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<Set<String>> oauthScopesSpec;

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        public Builder setClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = checkNotNull(clientSecretSpec);
            return this;
        }

        /// The scopes the [TeslaFleetModule]s storing the tokens request, which key those tokens; a set differing from theirs finds no token to revoke.
        public Builder setOauthScopes(BindingSpec<Set<String>> oauthScopesSpec) {
            this.oauthScopesSpec = checkNotNull(oauthScopesSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<StoredGrantRevoker> build() {
            return new TeslaFleetGrantRevokerModule(clientIdSpec, clientSecretSpec, oauthScopesSpec, specifiedAnnotation());
        }
    }
}
