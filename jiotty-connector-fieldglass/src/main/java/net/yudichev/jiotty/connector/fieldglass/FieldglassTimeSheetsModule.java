package net.yudichev.jiotty.connector.fieldglass;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

public final class FieldglassTimeSheetsModule extends BaseExposedKeyModule<FieldglassTimeSheetsClient> {
    private FieldglassTimeSheetsModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<FieldglassTimeSheetsClient, ?> builder() {
        return simpleBuilder(FieldglassTimeSheetsModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(FieldglassTimeSheetsClientImpl.class);
        expose(exposedKey);
    }
}
