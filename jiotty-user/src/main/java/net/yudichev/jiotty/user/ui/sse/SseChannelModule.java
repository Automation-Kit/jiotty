package net.yudichev.jiotty.user.ui.sse;

import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import org.jspecify.annotations.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/// Exposes an [SseChannel.Factory] whose channels escalate their own defects through the [AdminAlertService] the installing feature already uses, and count
/// their streams against the [SseAdmission] it names.
public final class SseChannelModule extends BaseExposedKeyModule<SseChannel.Factory> {
    private final BindingSpec<AdminAlertService> alertServiceSpec;
    private final BindingSpec<SseAdmission> admissionSpec;

    private SseChannelModule(SpecifiedAnnotation specifiedAnnotation,
                             BindingSpec<AdminAlertService> alertServiceSpec,
                             BindingSpec<SseAdmission> admissionSpec) {
        super(specifiedAnnotation);
        this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
        this.admissionSpec = checkNotNull(admissionSpec, "admissionSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        alertServiceSpec.bind(AdminAlertService.class)
                        .annotatedWith(SseChannel.Dependency.class)
                        .installedBy(this::installLifecycleComponentModule);
        admissionSpec.bind(SseAdmission.class)
                     .annotatedWith(SseChannel.Dependency.class)
                     .installedBy(this::installLifecycleComponentModule);
        install(new FactoryModuleBuilder().build(exposedKey));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<SseChannel.Factory, Builder> {
        /// `null` until [#setAlertService] is called.
        private @Nullable BindingSpec<AdminAlertService> alertServiceSpec;
        /// `null` until [#setAdmission] is called.
        private @Nullable BindingSpec<SseAdmission> admissionSpec;

        public Builder setAlertService(BindingSpec<AdminAlertService> alertServiceSpec) {
            this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
            return this;
        }

        /// Sets the budget the channels' streams count against: one shared by every channel of the kind it limits, or [SseAdmission#unlimited()].
        public Builder setAdmission(BindingSpec<SseAdmission> admissionSpec) {
            this.admissionSpec = checkNotNull(admissionSpec, "admissionSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<SseChannel.Factory> build() {
            return new SseChannelModule(specifiedAnnotation(),
                                        checkNotNull(alertServiceSpec, "setAlertService was not called"),
                                        checkNotNull(admissionSpec, "setAdmission was not called"));
        }
    }
}
