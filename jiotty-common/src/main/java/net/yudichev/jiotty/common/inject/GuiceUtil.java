package net.yudichev.jiotty.common.inject;

import com.google.inject.Key;
import com.google.inject.name.Names;

import java.lang.annotation.Annotation;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;

@SuppressWarnings("WeakerAccess") // it's a library
public final class GuiceUtil {
    private GuiceUtil() {
    }

    public static Annotation uniqueAnnotation() {
        return Names.named(UUID.randomUUID().toString());
    }

    /// The annotation type qualifying `key`, for a module that uses its own annotation as its identity — naming a log id, or naming the sibling binding it
    /// reads. Rejects an annotation *instance* such as [#uniqueAnnotation()], whose type is [com.google.inject.name.Named] and therefore identifies nothing:
    /// such a module must be given an annotation type, and failing here beats resolving a key nobody bound.
    public static Class<? extends Annotation> identifyingAnnotationType(Key<?> key) {
        Class<? extends Annotation> annotationType = key.getAnnotationType();
        checkArgument(annotationType != null, "an annotation is required: it identifies this instance");
        checkArgument(key.getAnnotation() == null, "an annotation type is required, not an instance of %s", annotationType);
        return annotationType;
    }
}
