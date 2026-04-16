package net.yudichev.jiotty.connector.expopush;

import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

class ExpoPushSenderModuleTest {
    @Test
    void build_withOnlyRequiredListener_exposesSender() {
        ExposedKeyModule<ExpoPushSender> module = ExpoPushSenderModule.builder()
                                                                      .setEventListener(literally(ExpoPushEventListener.NOOP))
                                                                      .build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(ExpoPushSender.class));

        var injector = Guice.createInjector(new ExecutorModule(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }

    @Test
    void build_withAccessTokenAndBaseUrl_exposesSender() {
        ExposedKeyModule<ExpoPushSender> module = ExpoPushSenderModule.builder()
                                                                      .setEventListener(literally(ExpoPushEventListener.NOOP))
                                                                      .withAccessToken(literally("secret-token"))
                                                                      .withBaseUrl(literally("http://localhost:1234"))
                                                                      .build();

        var injector = Guice.createInjector(new ExecutorModule(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }
}
