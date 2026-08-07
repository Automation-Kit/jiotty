package net.yudichev.jiotty.common.inject;

import com.google.inject.BindingAnnotation;
import com.google.inject.Guice;
import com.google.inject.Key;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forNoAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Pins the key derivation every single-service module inherits: the exposed key follows the [SpecifiedAnnotation] the builder carried, and the module binds
/// and exposes exactly that key.
class BaseExposedKeyModuleTest {
    @Test
    void derivesBareKeyWhenUnannotated() {
        assertThat(new TestModule(forNoAnnotation()).getExposedKey()).isEqualTo(Key.get(TestService.class));
    }

    @Test
    void derivesAnnotatedKeyFromAnnotationType() {
        assertThat(new TestModule(forAnnotation(Qualifier.class)).getExposedKey())
                .isEqualTo(Key.get(TestService.class, Qualifier.class));
    }

    @Test
    void derivesAnnotatedKeyFromAnnotationInstance() {
        var annotation = uniqueAnnotation();

        assertThat(new TestModule(forAnnotation(annotation)).getExposedKey()).isEqualTo(Key.get(TestService.class, annotation));
    }

    @Test
    void rejectsMissingAnnotationSpecification() {
        assertThatThrownBy(() -> new TestModule(null)).isInstanceOf(NullPointerException.class);
    }

    /// Two instances differing only by annotation have to coexist — that is the whole reason the exposed key is annotation-derived rather than
    /// `Key.get(T.class)`.
    @Test
    void exposesTheDerivedKeySoAnnotatedInstancesCoexist() {
        var first = new TestModule(forAnnotation(Qualifier.class));
        var second = new TestModule(forAnnotation(uniqueAnnotation()));

        var injector = Guice.createInjector(first, second);

        assertThat(injector.getInstance(first.getExposedKey())).isNotNull();
        assertThat(injector.getInstance(second.getExposedKey())).isNotSameAs(injector.getInstance(first.getExposedKey()));
    }

    private interface TestService {
    }

    private static final class TestServiceImpl implements TestService {
    }

    private static final class TestModule extends BaseExposedKeyModule<TestService> {
        TestModule(SpecifiedAnnotation specifiedAnnotation) {
            super(specifiedAnnotation);
        }

        @Override
        protected void configure() {
            bind(exposedKey).to(TestServiceImpl.class);
            expose(exposedKey);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Qualifier {
    }
}
