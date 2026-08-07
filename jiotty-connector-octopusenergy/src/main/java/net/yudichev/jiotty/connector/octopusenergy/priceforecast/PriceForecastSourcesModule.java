package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.List;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

/// Exposes the [PriceForecastSource]s in failover order.
public final class PriceForecastSourcesModule extends BaseExposedKeyModule<List<PriceForecastSource>> {
    private PriceForecastSourcesModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<List<PriceForecastSource>, ?> builder() {
        return simpleBuilder(PriceForecastSourcesModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).toProvider(registerLifecycleComponent(PriceForecastSourcesProvider.class));
        expose(exposedKey);
    }
}
