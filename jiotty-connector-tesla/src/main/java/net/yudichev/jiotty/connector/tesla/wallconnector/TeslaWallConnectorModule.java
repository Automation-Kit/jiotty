package net.yudichev.jiotty.connector.tesla.wallconnector;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

public final class TeslaWallConnectorModule extends BaseExposedKeyModule<TeslaWallConnector> {
    private final BindingSpec<String> hostAddressSpec;

    private TeslaWallConnectorModule(BindingSpec<String> hostAddressSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.hostAddressSpec = checkNotNull(hostAddressSpec);
    }

    @Override
    protected void configure() {
        hostAddressSpec.bind(String.class).annotatedWith(TeslaWallConnectorImpl.HostAddress.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(TeslaWallConnectorImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<TeslaWallConnector, Builder> {
        private BindingSpec<String> hostAddressSpec;

        public Builder setHostAddress(BindingSpec<String> hostAddressSpec) {
            this.hostAddressSpec = checkNotNull(hostAddressSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<TeslaWallConnector> build() {
            return new TeslaWallConnectorModule(hostAddressSpec, specifiedAnnotation());
        }
    }
}
