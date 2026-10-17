package net.yudichev.jiotty.connector.google.calendar;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.security.StoredGrantRevoker;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarGrantRevokerModuleTest {
    @Test
    void exposesARevokerUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<StoredGrantRevoker> module = GoogleCalendarGrantRevokerModule.builder().withAnnotation(forAnnotation(annotation)).build();

        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(StoredGrantRevoker.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isNotNull();
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }
}
