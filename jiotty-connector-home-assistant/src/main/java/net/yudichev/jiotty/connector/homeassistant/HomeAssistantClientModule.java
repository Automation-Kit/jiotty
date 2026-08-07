package net.yudichev.jiotty.connector.homeassistant;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class HomeAssistantClientModule extends BaseExposedKeyModule<HomeAssistantClient> {
    private final BindingSpec<String> baseUrlSpec;
    private final BindingSpec<String> accessTokenSpec;

    private HomeAssistantClientModule(BindingSpec<String> baseUrlSpec, BindingSpec<String> accessTokenSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.baseUrlSpec = checkNotNull(baseUrlSpec);
        this.accessTokenSpec = checkNotNull(accessTokenSpec);
    }

    @Override
    protected void configure() {
        baseUrlSpec.bind(String.class)
                   .annotatedWith(HomeAssistantClientImpl.BaseUrl.class)
                   .installedBy(this::installLifecycleComponentModule);
        accessTokenSpec.bind(String.class)
                       .annotatedWith(HomeAssistantClientImpl.AccessToken.class)
                       .installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(HomeAssistantClientImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<HomeAssistantClient, Builder> {
        private BindingSpec<String> baseUrlSpec;
        private BindingSpec<String> accessTokenSpec;

        public Builder setBaseUrl(BindingSpec<String> baseUrlSpec) {
            this.baseUrlSpec = checkNotNull(baseUrlSpec);
            return this;
        }

        public Builder setAccessToken(BindingSpec<String> accessTokenSpec) {
            this.accessTokenSpec = checkNotNull(accessTokenSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<HomeAssistantClient> build() {
            return new HomeAssistantClientModule(baseUrlSpec, accessTokenSpec, specifiedAnnotation());
        }
    }
}
