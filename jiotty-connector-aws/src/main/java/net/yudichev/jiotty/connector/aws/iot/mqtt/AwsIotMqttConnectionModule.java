package net.yudichev.jiotty.connector.aws.iot.mqtt;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;

public final class AwsIotMqttConnectionModule extends BaseExposedKeyModule<AwsIotMqttConnection> {
    private final String clientId;
    private final String clientEndpoint;
    private final Duration timeout;

    private AwsIotMqttConnectionModule(String clientId, String clientEndpoint, Duration timeout, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.clientId = checkNotNull(clientId);
        this.clientEndpoint = checkNotNull(clientEndpoint);
        this.timeout = checkNotNull(timeout);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        bindConstant().annotatedWith(AwsIotMqttConnectionImpl.ClientId.class).to(clientId);
        bindConstant().annotatedWith(AwsIotMqttConnectionImpl.ClientEndpoint.class).to(clientEndpoint);
        bind(Duration.class).annotatedWith(AwsIotMqttConnectionImpl.Timeout.class).toInstance(timeout);

        bind(exposedKey).to(registerLifecycleComponent(AwsIotMqttConnectionImpl.class));
        expose(exposedKey);
    }

    public static class Builder extends BaseModuleBuilder<AwsIotMqttConnection, Builder> {
        private String clientId;
        private String clientEndpoint;
        private Duration timeout;

        public Builder setClientId(String clientId) {
            this.clientId = checkNotNull(clientId);
            return this;
        }

        public Builder setClientEndpoint(String clientEndpoint) {
            this.clientEndpoint = checkNotNull(clientEndpoint);
            return this;
        }

        public Builder setTimeout(Duration timeout) {
            this.timeout = checkNotNull(timeout);
            return this;
        }

        @Override
        public ExposedKeyModule<AwsIotMqttConnection> build() {
            return new AwsIotMqttConnectionModule(clientId, clientEndpoint, timeout, specifiedAnnotation());
        }
    }
}