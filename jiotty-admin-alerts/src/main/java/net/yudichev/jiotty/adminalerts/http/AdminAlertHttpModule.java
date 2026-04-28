package net.yudichev.jiotty.adminalerts.http;

import com.google.inject.Singleton;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.user.ui.AuthenticatedHttpServerModule;
import net.yudichev.jiotty.user.ui.ServletMount;

import static com.google.common.base.Preconditions.checkNotNull;

/// Wires the admin-alert HTTP surface and exposes a [ServletMount] that callers plug into [AuthenticatedHttpServerModule] via `withServletMount(...)`.
public final class AdminAlertHttpModule extends BaseLifecycleComponentModule implements ExposedKeyModule<ServletMount> {
    private final BindingSpec<String> resolveTokenSpec;
    private final BindingSpec<AdminAlertService> alertServiceSpec;

    private AdminAlertHttpModule(BindingSpec<String> resolveTokenSpec, BindingSpec<AdminAlertService> alertServiceSpec) {
        this.resolveTokenSpec = checkNotNull(resolveTokenSpec, "resolveTokenSpec");
        this.alertServiceSpec = checkNotNull(alertServiceSpec, "alertServiceSpec");
    }

    public static Builder builder() {
        return new Builder();
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
        bind(getExposedKey()).to(AdminAlertServletMount.class).in(Singleton.class);
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<AdminAlertHttpModule> {
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
        public AdminAlertHttpModule build() {
            return new AdminAlertHttpModule(resolveTokenSpec, alertServiceSpec);
        }
    }
}
