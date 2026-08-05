package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.List;

/// Exposes the [PriceForecastSource]s in failover order.
public final class PriceForecastSourcesModule extends BaseLifecycleComponentModule implements ExposedKeyModule<List<PriceForecastSource>> {
    private final Key<List<PriceForecastSource>> exposedKey;

    private PriceForecastSourcesModule(SpecifiedAnnotation specifiedAnnotation) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<List<PriceForecastSource>> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        bind(exposedKey).toProvider(registerLifecycleComponent(PriceForecastSourcesProvider.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<List<PriceForecastSource>, Builder> {
        private Builder() {
        }

        @Override
        public ExposedKeyModule<List<PriceForecastSource>> build() {
            return new PriceForecastSourcesModule(specifiedAnnotation());
        }
    }
}
