package net.yudichev.jiotty.common.inject;

import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuiceUtilTest {
    @Test
    void uniqueAnnotation() {
        assertThat(GuiceUtil.uniqueAnnotation()).isNotEqualTo(GuiceUtil.uniqueAnnotation());
    }

    @Test
    void identifyingAnnotationTypeReturnsTheQualifyingType() {
        assertThat(GuiceUtil.identifyingAnnotationType(Key.get(String.class, Identity.class))).isEqualTo(Identity.class);
    }

    @Test
    void identifyingAnnotationTypeRejectsUnannotatedKey() {
        assertThatThrownBy(() -> GuiceUtil.identifyingAnnotationType(Key.get(String.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an annotation is required");
    }

    /// An instance annotation resolves to a type that identifies nothing, so a module that reads a sibling binding by that type would look up a key nobody
    /// bound — see [GuiceUtil#identifyingAnnotationType(Key)].
    @Test
    void identifyingAnnotationTypeRejectsAnnotationInstance() {
        assertThatThrownBy(() -> GuiceUtil.identifyingAnnotationType(Key.get(String.class, GuiceUtil.uniqueAnnotation())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("an annotation type is required, not an instance");
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Identity {
    }
}
