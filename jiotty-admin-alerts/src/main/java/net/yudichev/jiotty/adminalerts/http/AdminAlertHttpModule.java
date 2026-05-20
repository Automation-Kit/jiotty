package net.yudichev.jiotty.adminalerts.http;

import com.google.inject.Key;
import com.google.inject.Singleton;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.user.ui.AuthenticatedHttpServerModule;
import net.yudichev.jiotty.user.ui.ServletMount;

import static com.google.common.base.Preconditions.checkNotNull;

/// Wires the admin-alert HTTP surface and exposes a [ServletMount] that callers plug into [AuthenticatedHttpServerModule] via
/// [AuthenticatedHttpServerModule.Builder#addServletMount].
public final class AdminAlertHttpModule extends BaseLifecycleComponentModule implements ExposedKeyModule<ServletMount> {
    private final BindingSpec<String> resolveTokenSpec;
    private final BindingSpec<AdminAlertService> alertServiceSpec;
    private final Key<ServletMount> exposedKey;

    private AdminAlertHttpModule(SpecifiedAnnotation specifiedAnnotation,
                                 BindingSpec<String> resolveTokenSpec,
                                 BindingSpec<AdminAlertService> alertServiceSpec) {
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
        this.resolveTokenSpec = checkNotNull(resolveTokenSpec, "resolveTokenSpec");
        this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Key<ServletMount> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        resolveTokenSpec.bind(String.class)
                        .annotatedWith(AdminBearerAuthFilter.ResolveToken.class)
                        .installedBy(this::installLifecycleComponentModule);
        alertServiceSpec.bind(AdminAlertService.class)
                        .annotatedWith(AdminResolveServlet.Dependency.class)
                        .installedBy(this::installLifecycleComponentModule);
        bind(AdminBearerAuthFilter.class).in(Singleton.class);
        bind(AdminResolveServlet.class).in(Singleton.class);
        bind(exposedKey).to(AdminAlertServletMount.class).in(Singleton.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<ServletMount, Builder> {
        private BindingSpec<String> resolveTokenSpec;
        private BindingSpec<AdminAlertService> alertServiceSpec;

        public Builder setResolveToken(BindingSpec<String> resolveTokenSpec) {
            this.resolveTokenSpec = checkNotNull(resolveTokenSpec, "resolveTokenSpec");
            return this;
        }

        public Builder setAlertService(BindingSpec<AdminAlertService> alertServiceSpec) {
            this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
            return this;
        }

        @Override
        public ExposedKeyModule<ServletMount> build() {
            return new AdminAlertHttpModule(specifiedAnnotation(), resolveTokenSpec, alertServiceSpec);
        }
    }
}
