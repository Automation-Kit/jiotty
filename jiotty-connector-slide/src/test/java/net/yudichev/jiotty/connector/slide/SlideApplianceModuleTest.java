package net.yudichev.jiotty.connector.slide;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SlideApplianceModuleTest {
    @ParameterizedTest
    @MethodSource
    void test(ExposedKeyModule<SlideService> slideServiceModule) {
        ExposedKeyModule<Appliance> applianceModule = SlideApplianceModule.builder()
                                                                          .setSlideIdSpec(literally(1L))
                                                                          .withRetries(literally(BackOffConfig.builder().build()))
                                                                          .build();
        Injector injector = Guice.createInjector(
                TimeModule.builder().build(),
                ExecutorModule.builder().build(),
                slideServiceModule,
                applianceModule);

        assertThat(applianceModule.getExposedKey()).isEqualTo(Key.get(Appliance.class));
        injector.getBinding(applianceModule.getExposedKey());
    }

    public static Stream<ExposedKeyModule<SlideService>> test() {
        return Stream.of(
                SlideServiceModule.builder()
                                  .setCloutConnection(literally("email"), literally("password"))
                                  .withPositionVerification()
                                  .build(),
                SlideServiceModule.builder()
                                  .setLocalConnection(literally("host"), literally("code"))
                                  .withPositionVerification()
                                  .build()
        );
    }
}