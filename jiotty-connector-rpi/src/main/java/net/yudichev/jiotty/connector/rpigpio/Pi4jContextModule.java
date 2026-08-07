package net.yudichev.jiotty.connector.rpigpio;

import com.pi4j.context.Context;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

public final class Pi4jContextModule extends BaseExposedKeyModule<Context> {
    private Pi4jContextModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<Context, ?> builder() {
        return simpleBuilder(Pi4jContextModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).toProvider(registerLifecycleComponent(Pi4jContextProvider.class));
        expose(exposedKey);
    }
}
