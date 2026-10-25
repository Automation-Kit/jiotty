package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Exposes the [PriceForecastSource]s in failover order.
public final class PriceForecastSourcesModule extends BaseExposedKeyModule<List<PriceForecastSource>> {
    private final BindingSpec<Optional<String>> userAgentSpec;

    private PriceForecastSourcesModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<Optional<String>> userAgentSpec) {
        super(specifiedAnnotation);
        this.userAgentSpec = checkNotNull(userAgentSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        userAgentSpec.bind(new TypeLiteral<>() {})
                     .annotatedWith(PriceForecastSourcesProvider.UserAgent.class)
                     .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).toProvider(registerLifecycleComponent(PriceForecastSourcesProvider.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<List<PriceForecastSource>, Builder> {
        private BindingSpec<Optional<String>> userAgentSpec = literally(Optional.empty());

        /// Sets the User-Agent the sources' requests carry. Unset, OkHttp's default goes out.
        public Builder withUserAgent(BindingSpec<String> userAgentSpec) {
            this.userAgentSpec = userAgentSpec.map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        @Override
        public ExposedKeyModule<List<PriceForecastSource>> build() {
            return new PriceForecastSourcesModule(specifiedAnnotation(), userAgentSpec);
        }
    }
}
