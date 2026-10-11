package net.yudichev.jiotty.user.ui.sse;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;

/// Exposes an [SseChannel.Factory] whose channels escalate their own defects through the [AdminAlertService] the installing feature already uses.
public final class SseChannelModule extends BaseExposedKeyModule<SseChannel.Factory> {
    private final BindingSpec<AdminAlertService> alertServiceSpec;

    private SseChannelModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<AdminAlertService> alertServiceSpec) {
        super(specifiedAnnotation);
        this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        alertServiceSpec.bind(AdminAlertService.class)
                        .annotatedWith(SseChannel.Dependency.class)
                        .installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder().build(exposedKey));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<SseChannel.Factory, Builder> {
        private BindingSpec<AdminAlertService> alertServiceSpec;

        public Builder setAlertService(BindingSpec<AdminAlertService> alertServiceSpec) {
            this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<SseChannel.Factory> build() {
            return new SseChannelModule(specifiedAnnotation(), alertServiceSpec);
        }
    }
}
