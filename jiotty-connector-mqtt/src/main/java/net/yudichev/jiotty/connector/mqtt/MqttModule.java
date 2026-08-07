package net.yudichev.jiotty.connector.mqtt;

import com.google.inject.TypeLiteral;
import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.throttling.ThresholdThrottlingConsumerModule;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

public final class MqttModule extends BaseExposedKeyModule<Mqtt> {
    private final BindingSpec<String> serverUriSpec;
    private final BindingSpec<String> clientIdSpec;
    private final BindingSpec<Consumer<MqttConnectOptions>> connectionOptionsCustomiserSpec;

    private MqttModule(BindingSpec<String> serverUriSpec,
                       BindingSpec<String> clientIdSpec,
                       BindingSpec<Consumer<MqttConnectOptions>> connectionOptionsCustomiserSpec,
                       SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.serverUriSpec = checkNotNull(serverUriSpec);
        this.clientIdSpec = checkNotNull(clientIdSpec);
        this.connectionOptionsCustomiserSpec = checkNotNull(connectionOptionsCustomiserSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        serverUriSpec.bind(String.class).annotatedWith(MqttClientProvider.ServerUri.class).installedBy(this::installLifecycleComponentModule);
        clientIdSpec.bind(String.class).annotatedWith(MqttClientProvider.ClientId.class).installedBy(this::installLifecycleComponentModule);
        bind(IMqttAsyncClient.class).toProvider(MqttClientProvider.class).in(Singleton.class);

        connectionOptionsCustomiserSpec.bind(new TypeLiteral<>() {})
                                       .annotatedWith(MqttImpl.Dependency.class)
                                       .installedBy(this::installLifecycleComponentModule);

        installLifecycleComponentModule(ThresholdThrottlingConsumerModule.builder()
                                                                         .setValueType(Throwable.class)
                                                                         .withAnnotation(forAnnotation(MqttImpl.Dependency.class))
                                                                         .build());

        bind(exposedKey).to(registerLifecycleComponent(MqttImpl.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<Mqtt, Builder> {
        private BindingSpec<String> serverUriSpec;
        private BindingSpec<String> clientIdSpec;
        private BindingSpec<Consumer<MqttConnectOptions>> connectionOptionsCustomiserSpec = BindingSpec.literally(ignored -> {});

        public Builder setServerUri(BindingSpec<String> serverUriSpec) {
            this.serverUriSpec = checkNotNull(serverUriSpec);
            return this;
        }

        public Builder setClientId(BindingSpec<String> clientIdSpec) {
            this.clientIdSpec = checkNotNull(clientIdSpec);
            return this;
        }

        public Builder withConnectionOptionsCustomised(BindingSpec<Consumer<MqttConnectOptions>> connectionOptionsCustomiserSpec) {
            this.connectionOptionsCustomiserSpec = checkNotNull(connectionOptionsCustomiserSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<Mqtt> build() {
            return new MqttModule(serverUriSpec, clientIdSpec, connectionOptionsCustomiserSpec, specifiedAnnotation());
        }
    }
}
