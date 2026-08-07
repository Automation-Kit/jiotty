package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.connector.mqtt.Mqtt;

import static com.google.common.base.Preconditions.checkNotNull;

public final class MqttTeslaTelemetryModule extends BaseExposedKeyModule<TeslaTelemetryFactory> {
    private final BindingSpec<Mqtt> mqttSpec;
    private final BindingSpec<String> topicBaseSpec;

    private MqttTeslaTelemetryModule(BindingSpec<Mqtt> mqttSpec, BindingSpec<String> topicBaseSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.mqttSpec = checkNotNull(mqttSpec);
        this.topicBaseSpec = checkNotNull(topicBaseSpec);
    }

    @Override
    protected void configure() {
        mqttSpec.bind(Mqtt.class).annotatedWith(MqttTeslaTelemetry.Dependency.class).installedBy(this::installLifecycleComponentModule);
        topicBaseSpec.bind(String.class).annotatedWith(MqttTeslaTelemetry.TopicBase.class).installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder()
                        .implement(TeslaTelemetry.class, MqttTeslaTelemetry.class)
                        .build(exposedKey));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<TeslaTelemetryFactory, Builder> {
        private BindingSpec<Mqtt> mqttSpec = BindingSpec.boundTo(Mqtt.class);
        private BindingSpec<String> topicBaseSpec = BindingSpec.literally("tesla/telemetry");

        public Builder withMqtt(BindingSpec<Mqtt> mqttSpec) {
            this.mqttSpec = checkNotNull(mqttSpec);
            return this;
        }

        public Builder withTopicBase(BindingSpec<String> topicBaseSpec) {
            this.topicBaseSpec = checkNotNull(topicBaseSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<TeslaTelemetryFactory> build() {
            return new MqttTeslaTelemetryModule(mqttSpec, topicBaseSpec, specifiedAnnotation());
        }
    }
}
