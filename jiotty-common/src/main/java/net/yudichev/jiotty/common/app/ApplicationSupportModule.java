package net.yudichev.jiotty.common.app;

import com.google.inject.AbstractModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

final class ApplicationSupportModule extends AbstractModule {
    private final SpecifiedAnnotation specifiedAnnotation;
    private final ApplicationLifecycleControl applicationLifecycleControl;

    ApplicationSupportModule(SpecifiedAnnotation specifiedAnnotation, ApplicationLifecycleControl applicationLifecycleControl) {
        this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
        this.applicationLifecycleControl = checkNotNull(applicationLifecycleControl);
    }

    @Override
    protected void configure() {
        bind(specifiedAnnotation.specify(ApplicationLifecycleControl.class)).toInstance(applicationLifecycleControl);
    }
}
