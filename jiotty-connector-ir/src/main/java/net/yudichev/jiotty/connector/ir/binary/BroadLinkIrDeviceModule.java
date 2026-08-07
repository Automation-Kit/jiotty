package net.yudichev.jiotty.connector.ir.binary;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class BroadLinkIrDeviceModule extends BaseExposedKeyModule<IrDevice> {
    private final String host;
    private final String macAddress;

    private BroadLinkIrDeviceModule(String host, String macAddress, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.host = checkNotNull(host);
        this.macAddress = checkNotNull(macAddress);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        bindConstant().annotatedWith(BroadLinkIrDevice.Host.class).to(host);
        bindConstant().annotatedWith(BroadLinkIrDevice.MacAddress.class).to(macAddress);
        bind(exposedKey).to(registerLifecycleComponent(BroadLinkIrDevice.class));
        expose(exposedKey);
    }

    public static class Builder extends BaseModuleBuilder<IrDevice, Builder> {
        private String host;
        private String macAddress;

        public Builder setHost(String host) {
            this.host = checkNotNull(host);
            return this;
        }

        public Builder setMacAddress(String macAddress) {
            this.macAddress = checkNotNull(macAddress);
            return this;
        }

        @Override
        public ExposedKeyModule<IrDevice> build() {
            return new BroadLinkIrDeviceModule(host, macAddress, specifiedAnnotation());
        }
    }
}
