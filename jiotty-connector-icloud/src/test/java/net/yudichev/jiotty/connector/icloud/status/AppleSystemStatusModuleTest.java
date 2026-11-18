package net.yudichev.jiotty.connector.icloud.status;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Duration;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class AppleSystemStatusModuleTest {
    @Test
    void configuresAResolvableSourceUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        BackOffConfig retryBackOffConfig = BackOffConfig.builder().setMaxElapsedTime(Duration.ofSeconds(10)).build();
        ExposedKeyModule<AppleSystemStatusSource> module = AppleSystemStatusModule.builder()
                                                                                  .withTimeout(literally(Duration.ofSeconds(5)))
                                                                                  .withRetryBackOffConfig(literally(retryBackOffConfig))
                                                                                  .withAnnotation(forAnnotation(annotation))
                                                                                  .build();
        Injector injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(AppleSystemStatusSource.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(AppleSystemStatusSourceImpl.class);
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    @Test
    void theDefaultsResolve() {
        ExposedKeyModule<AppleSystemStatusSource> module = AppleSystemStatusModule.builder().build();
        Injector injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(injector.getInstance(module.getExposedKey())).isNotNull();
    }
}
