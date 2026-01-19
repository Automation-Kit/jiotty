package net.yudichev.jiotty.connector.google.maps;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;

public final class RoutesServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<RoutesService> {
    private final BindingSpec<String> apiKeySpec;
    private final Key<RoutesService> exposedKey;

    public RoutesServiceModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<String> apiKeySpec) {
        this.apiKeySpec = checkNotNull(apiKeySpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    protected void configure() {
        apiKeySpec.bind(String.class).annotatedWith(Bindings.ApiKey.class).installedBy(this::installLifecycleComponentModule);

        bind(exposedKey).to(registerLifecycleComponent(RoutesServiceImpl.class));
        expose(exposedKey);
    }

    @Override
    public Key<RoutesService> getExposedKey() {
        return exposedKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<RoutesService>>, HasWithAnnotation {
        private BindingSpec<String> apiKeySpec;
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();

        public Builder setApiKey(BindingSpec<String> apiKeySpec) {
            this.apiKeySpec = checkNotNull(apiKeySpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public ExposedKeyModule<RoutesService> build() {
            return new RoutesServiceModule(specifiedAnnotation, apiKeySpec);
        }
    }
}
