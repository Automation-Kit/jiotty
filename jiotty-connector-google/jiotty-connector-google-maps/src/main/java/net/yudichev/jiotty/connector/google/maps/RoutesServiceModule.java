package net.yudichev.jiotty.connector.google.maps;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class RoutesServiceModule extends BaseExposedKeyModule<RoutesService> {
    private final BindingSpec<String> apiKeySpec;

    private RoutesServiceModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<String> apiKeySpec) {
        super(specifiedAnnotation);
        this.apiKeySpec = checkNotNull(apiKeySpec);
    }

    @Override
    protected void configure() {
        apiKeySpec.bind(String.class).annotatedWith(Bindings.ApiKey.class).installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(RoutesServiceImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<RoutesService, Builder> {
        private BindingSpec<String> apiKeySpec;

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        @Override
        public ExposedKeyModule<RoutesService> build() {
            return new RoutesServiceModule(specifiedAnnotation(), apiKeySpec);
        }
    }
}
