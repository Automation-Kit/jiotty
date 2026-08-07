package net.yudichev.jiotty.connector.shelly;

import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

class ShellyPlugModuleTest {
    @Test
    void configures() {
        var module = ShellyPlugModule.builder()
                                     .setHost(BindingSpec.literally("host"))
                                     .withAnnotation(SpecifiedAnnotation.forAnnotation(TheAnnotation.class))
                                     .build();
        Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module)
             .getBinding(Key.get(ShellyPlug.class, TheAnnotation.class));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface TheAnnotation {
    }
}