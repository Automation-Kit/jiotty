package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import net.yudichev.jiotty.common.async.SchedulingExecutor;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

final class Bindings {
    private Bindings() {
    }

    /// Binding annotation for the per-[UIServer] single-threaded [SchedulingExecutor]. All [UIServer]-scoped components ([OptionRegistry],
    /// [DisplayableRegistry], [SseService], every [ApiPathHandler]) share this one executor so their state mutations stay serialised.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface UIExecutor {
    }
}
