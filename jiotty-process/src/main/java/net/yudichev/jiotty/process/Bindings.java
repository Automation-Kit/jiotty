package net.yudichev.jiotty.process;

import com.google.inject.BindingAnnotation;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class Bindings {
    private Bindings() {
    }

    /// Qualifies the [ApplicationLifecycleControl] of the application [AppStarter] runs the process in, which a panic of the app
    /// escalates to. A test that installs [InitModule] outside [AppStarter] binds it itself.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface ProcessApplication {
    }
}
