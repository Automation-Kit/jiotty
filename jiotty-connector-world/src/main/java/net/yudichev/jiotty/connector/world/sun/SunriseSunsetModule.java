package net.yudichev.jiotty.connector.world.sun;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

public final class SunriseSunsetModule extends BaseExposedKeyModule<SunriseSunsetServiceFactory> {
    private SunriseSunsetModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<SunriseSunsetServiceFactory, ?> builder() {
        return simpleBuilder(SunriseSunsetModule::new);
    }

    @Override
    protected void configure() {
        install(new FactoryModuleBuilder()
                        .implement(SunriseSunsetService.class, SunriseSunsetServiceImpl.class)
                        .build(exposedKey));
        expose(exposedKey);
    }
}
