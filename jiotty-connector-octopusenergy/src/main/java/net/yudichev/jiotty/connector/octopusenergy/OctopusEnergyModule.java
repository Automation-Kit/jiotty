package net.yudichev.jiotty.connector.octopusenergy;

import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

public final class OctopusEnergyModule extends BaseLifecycleComponentModule implements ExposedKeyModule<OctopusEnergy> {
    private final Key<OctopusEnergy> exposedKey;

    private OctopusEnergyModule(SpecifiedAnnotation specifiedAnnotation) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<OctopusEnergy> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        bind(exposedKey).to(registerLifecycleComponent(OctopusEnergyImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<OctopusEnergy, Builder> {
        @Override
        public ExposedKeyModule<OctopusEnergy> build() {
            return new OctopusEnergyModule(specifiedAnnotation());
        }
    }
}
