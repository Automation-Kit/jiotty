package net.yudichev.jiotty.user.ui.sse;

import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

/// Exposes one metered [SseAdmission] budget, to be shared by every channel whose streams count against it. Install it once per server, since its meters are
/// registered under fixed names.
public final class SseAdmissionModule extends BaseExposedKeyModule<SseAdmission> {
    private SseAdmissionModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<SseAdmission, ?> builder() {
        return simpleBuilder(SseAdmissionModule::new);
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(SseAdmissionImpl.class);
        bind(SseAdmissionImpl.class).in(Singleton.class);
        expose(exposedKey);
    }
}
