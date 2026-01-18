package net.yudichev.jiotty.connector.mqtt.presence;

import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.connector.mqtt.Mqtt;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class MqttPresenceServiceModule extends BaseLifecycleComponentModule implements ExposedKeyModule<MqttPresenceService> {
    private final BindingSpec<String> nameSpec;
    private final BindingSpec<Mqtt> mqttSpec;
    private final BindingSpec<String> topicSpec;
    private final Key<MqttPresenceService> key;

    private MqttPresenceServiceModule(BindingSpec<String> nameSpec,
                                      BindingSpec<Mqtt> mqttSpec,
                                      BindingSpec<String> topicSpec,
                                      SpecifiedAnnotation specifiedAnnotation) {
        this.nameSpec = checkNotNull(nameSpec);
        this.mqttSpec = checkNotNull(mqttSpec);
        this.topicSpec = checkNotNull(topicSpec);
        key = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public Key<MqttPresenceService> getExposedKey() {
        return key;
    }

    @Override
    protected void configure() {
        mqttSpec.bind(Mqtt.class).annotatedWith(Dependency.class).installedBy(this::installLifecycleComponentModule);
        topicSpec.bind(String.class).annotatedWith(Topic.class).installedBy(this::installLifecycleComponentModule);
        nameSpec.bind(String.class).annotatedWith(Name.class).installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(registerLifecycleComponent(MqttPresenceServiceImpl.class));
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Topic {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Name {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    public static final class Builder implements TypedBuilder<ExposedKeyModule<MqttPresenceService>>, HasWithAnnotation {
        private BindingSpec<String> nameSpec;
        private BindingSpec<Mqtt> mqttSpec;
        private BindingSpec<String> topicSpec;
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        public Builder setName(BindingSpec<String> nameSpec) {
            this.nameSpec = checkNotNull(nameSpec);
            return this;
        }

        public Builder setMqtt(BindingSpec<Mqtt> mqttSpec) {
            this.mqttSpec = checkNotNull(mqttSpec);
            return this;
        }

        public Builder setTopic(BindingSpec<String> topicSpec) {
            this.topicSpec = checkNotNull(topicSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<MqttPresenceService> build() {
            return new MqttPresenceServiceModule(nameSpec, mqttSpec, topicSpec, specifiedAnnotation);
        }
    }
}
