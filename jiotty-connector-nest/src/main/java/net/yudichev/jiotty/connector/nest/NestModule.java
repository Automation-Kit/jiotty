package net.yudichev.jiotty.connector.nest;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class NestModule extends BaseExposedKeyModule<NestThermostat> {
    private final String accessToken;
    private final String deviceId;

    private NestModule(String accessToken, String deviceId, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.accessToken = checkNotNull(accessToken);
        this.deviceId = checkNotNull(deviceId);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        bindConstant().annotatedWith(NestThermostatImpl.AccessToken.class).to(accessToken);
        bindConstant().annotatedWith(NestThermostatImpl.DeviceId.class).to(deviceId);
        bind(exposedKey).to(registerLifecycleComponent(NestThermostatImpl.class));
        expose(exposedKey);
    }

    public static class Builder extends BaseModuleBuilder<NestThermostat, Builder> {
        private String accessToken;
        private String deviceId;

        public Builder setAccessToken(String accessToken) {
            this.accessToken = checkNotNull(accessToken);
            return this;
        }

        public Builder setDeviceId(String deviceId) {
            this.deviceId = checkNotNull(deviceId);
            return this;
        }

        @Override
        public ExposedKeyModule<NestThermostat> build() {
            return new NestModule(accessToken, deviceId, specifiedAnnotation());
        }
    }
}
