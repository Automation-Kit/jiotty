package net.yudichev.jiotty.connector.google.maps;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class GeocodingServiceModule extends BaseExposedKeyModule<GeocodingService> {
    private final BindingSpec<String> apiKeySpec;

    public GeocodingServiceModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<String> apiKeySpec) {
        super(specifiedAnnotation);
        this.apiKeySpec = checkNotNull(apiKeySpec);
    }

    @Override
    protected void configure() {
        apiKeySpec.bind(String.class).annotatedWith(Bindings.ApiKey.class).installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(GeocodingServiceImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<GeocodingService, Builder> {
        private BindingSpec<String> apiKeySpec;

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        @Override
        public ExposedKeyModule<GeocodingService> build() {
            return new GeocodingServiceModule(specifiedAnnotation(), apiKeySpec);
        }
    }
}
