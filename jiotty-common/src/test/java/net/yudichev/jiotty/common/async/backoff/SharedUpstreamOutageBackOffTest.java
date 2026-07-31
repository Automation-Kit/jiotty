package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.async.backoff.SharedUpstreamOutageBackOff.sharedOutageRetryExecutorModule;
import static org.assertj.core.api.Assertions.assertThat;

class SharedUpstreamOutageBackOffTest {

    @Test
    void sharedOutageRetryExecutorModule_exposesAnnotatedRetryExecutor() {
        ExposedKeyModule<RetryableOperationExecutor> module = sharedOutageRetryExecutorModule("test-api-retry",
                                                                                              Dependency.class,
                                                                                              BackOffConfig.builder().build());

        assertThat(module.getExposedKey()).isEqualTo(Key.get(RetryableOperationExecutor.class, Dependency.class));
        var injector = Guice.createInjector(new ExecutorModule(), new BaseLifecycleComponentModule() {
            @Override
            protected void configure() {
                installLifecycleComponentModule(module);
                expose(module.getExposedKey());
            }
        });
        assertThat(injector.getInstance(module.getExposedKey())).isNotNull();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
