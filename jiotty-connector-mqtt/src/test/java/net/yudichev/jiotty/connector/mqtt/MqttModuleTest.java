package net.yudichev.jiotty.connector.mqtt;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class MqttModuleTest {
    @Test
    void injector() {
        Named annotation = Names.named("a");
        ExposedKeyModule<Mqtt> module = MqttModule.builder()
                                                  .setClientId(literally("ci"))
                                                  .setServerUri(literally("su"))
                                                  .withConnectionOptionsCustomised(BindingSpec.literally(options -> options.setUserName("u")))
                                                  .withAnnotation(forAnnotation(annotation))
                                                  .build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(Mqtt.class, annotation));

        Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module).getBinding(module.getExposedKey());
    }
}