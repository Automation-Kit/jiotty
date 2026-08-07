package net.yudichev.jiotty.common.keystore;

import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class KeyStoreEntryModuleTest {
    /// The exposed type is a bare [String], so two entries built without an explicit annotation must still get distinct keys — otherwise the second collides
    /// with the first in the same injector.
    @Test
    void defaultsToAUniqueAnnotationPerEntry() {
        var first = KeyStoreEntryModule.builder().setAlias(literally("first")).build();
        var second = KeyStoreEntryModule.builder().setAlias(literally("second")).build();

        assertThat(first.getExposedKey()).isNotEqualTo(Key.get(String.class));
        assertThat(first.getExposedKey()).isNotEqualTo(second.getExposedKey());
    }

    @Test
    void keyStoreEntryHelperAlsoGetsAUniqueKeyPerAlias() {
        assertThat(KeyStoreEntryModule.keyStoreEntry("first")).isNotEqualTo(KeyStoreEntryModule.keyStoreEntry("second"));
    }

    @Test
    void honoursAnExplicitlyRequestedAnnotation() {
        var module = KeyStoreEntryModule.builder()
                                        .setAlias(literally("the-alias"))
                                        .withAnnotation(forAnnotation(Alias.class))
                                        .build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(String.class, Alias.class));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Alias {
    }
}
