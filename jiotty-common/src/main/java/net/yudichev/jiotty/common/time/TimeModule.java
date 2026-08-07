package net.yudichev.jiotty.common.time;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

public final class TimeModule extends BaseExposedKeyModule<CurrentDateTimeProvider> {
    private TimeModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<CurrentDateTimeProvider, ?> builder() {
        return simpleBuilder(TimeModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(TimeProvider.class);
        expose(exposedKey);
    }
}
