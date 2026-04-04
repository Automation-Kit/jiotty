package net.yudichev.jiotty.process;

import com.google.inject.BindingAnnotation;
import com.google.inject.Injector;
import com.google.inject.Module;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

final class AppManager extends BaseLifecycleComponent {
    private final Function<Injector, Module> appModuleFactory;
    private final Injector injector;
    private Application application;

    @Inject
    public AppManager(@Dependency Function<Injector, Module> appModuleFactory, Injector injector) {
        this.appModuleFactory = checkNotNull(appModuleFactory);
        this.injector = checkNotNull(injector);
    }

    @Override
    protected void doStart() {
        application = Application.builder()
                                 .setName("app")
                                 .addModule(() -> appModuleFactory.apply(injector))
                                 .withParentInjector(injector)
                                 .build();
        try {
            try {
                application.start();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } catch (RuntimeException e) {
            application.stop();
            throw e;
        }
    }

    @Override
    protected void doStop() {
        if (application != null) {
            application.stop();
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
