package net.yudichev.jiotty.connector.world.sun;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

public final class SunriseSunsetTimesModule extends BaseExposedKeyModule<SunriseSunsetTimes> {
    private SunriseSunsetTimesModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<SunriseSunsetTimes, ?> builder() {
        return simpleBuilder(SunriseSunsetTimesModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(registerLifecycleComponent(SunriseSunsetTimesImpl.class));
        expose(exposedKey);
    }
}
