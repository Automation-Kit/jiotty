package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.net.SslCustomisation;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class TeslaFleetPartnerModule extends BaseExposedKeyModule<TeslaFleetPartner> {
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<String> clientSecretSpec;
    private final BindingSpec<String> scopeSpec;
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec;

    private TeslaFleetPartnerModule(BindingSpec<String> clientIdSpec,
                                    BindingSpec<String> clientSecretSpec,
                                    BindingSpec<String> scopeSpec,
                                    BindingSpec<String> baseUrlSpec,
                                    BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec,
                                    SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.clientSecretSpec = checkNotNull(clientSecretSpec);
        this.scopeSpec = checkNotNull(scopeSpec);
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
        this.sslCustomisationSpec = checkNotNull(sslCustomisationSpec);
    }

    @Override
    protected void configure() {
        clientIdSpec.bind(String.class).annotatedWith(TeslaFleetPartnerImpl.ClientId.class).installedBy(this::installLifecycleComponentModule);
        clientSecretSpec.bind(String.class).annotatedWith(TeslaFleetPartnerImpl.ClientSecret.class).installedBy(this::installLifecycleComponentModule);
        scopeSpec.bind(String.class).annotatedWith(TeslaFleetPartnerImpl.Scope.class).installedBy(this::installLifecycleComponentModule);
        baseUrlSpec.bind(String.class).annotatedWith(TeslaFleetPartnerImpl.BaseUrl.class).installedBy(this::installLifecycleComponentModule);
        sslCustomisationSpec.bind(new TypeLiteral<>() {})
                            .annotatedWith(TeslaFleetPartnerImpl.Dependency.class)
                            .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(TeslaFleetPartnerImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<TeslaFleetPartner, Builder> {
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<String> clientSecretSpec;
        private BindingSpec<String> scopeSpec = literally("openid offline_access");
        private BindingSpec<String> baseUrlSpec = literally(TeslaHttp.AUDIENCE + "/api/1");
        private BindingSpec<Optional<SslCustomisation>> sslCustomisationSpec = literally(Optional.empty());

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        public Builder setClientSecret(BindingSpec<String> clientSecretSpec) {
            this.clientSecretSpec = checkNotNull(clientSecretSpec);
            return this;
        }

        public Builder withScope(BindingSpec<String> scopeSpec) {
            this.scopeSpec = checkNotNull(scopeSpec);
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

        @Override
        public ExposedKeyModule<TeslaFleetPartner> build() {
            return new TeslaFleetPartnerModule(clientIdSpec, clientSecretSpec, scopeSpec, baseUrlSpec, sslCustomisationSpec, specifiedAnnotation());
        }
    }
}
