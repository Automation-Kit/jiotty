package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import com.google.inject.multibindings.OptionalBinder;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class AuthenticatedHttpServerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<UIHttpServer> {
    public static final String PATH_ROOT = "/ui";
    public static final String SUB_PATH_OPTIONS = "/options";

    private final BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
    private final BindingSpec<Integer> listenPortSpec;
    private final @Nullable BindingSpec<ServletMount> servletMountSpec;

    private AuthenticatedHttpServerModule(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec,
                                          BindingSpec<Integer> listenPortSpec,
                                          @Nullable BindingSpec<ServletMount> servletMountSpec) {
        this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
        this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
        this.servletMountSpec = servletMountSpec;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        userTokenAuthoriserSpec.bind(UserTokenAuthoriser.class)
                               .annotatedWith(AuthenticatedUIRequestAuthoriser.Dependency.class)
                               .installedBy(this::installLifecycleComponentModule);
        listenPortSpec.bind(int.class).annotatedWith(UIHttpServerImpl.ListenPort.class).installedBy(this::installLifecycleComponentModule);
        bind(UIRequestAuthoriser.class).annotatedWith(UIHttpServerImpl.Dependency.class).to(AuthenticatedUIRequestAuthoriser.class);
        OptionalBinder<ServletMount> mountBinder = OptionalBinder.newOptionalBinder(binder(), ServletMount.class);
        if (servletMountSpec != null) {
            servletMountSpec.bind(ServletMount.class)
                            .annotatedWith(InternalServletMount.class)
                            .installedBy(this::installLifecycleComponentModule);
            mountBinder.setBinding().to(Key.get(ServletMount.class, InternalServletMount.class));
        }
        bind(getExposedKey()).to(registerLifecycleComponent(UIHttpServerImpl.class));
        expose(getExposedKey());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface InternalServletMount {
    }

    public static final class Builder implements TypedBuilder<AuthenticatedHttpServerModule> {
        private BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
        private BindingSpec<Integer> listenPortSpec = BindingSpec.literally(0);
        private @Nullable BindingSpec<ServletMount> servletMountSpec;

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
            return this;
        }

        public Builder setUserTokenAuthoriser(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec) {
            this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
            return this;
        }

        public Builder withServletMount(BindingSpec<ServletMount> servletMountSpec) {
            this.servletMountSpec = checkNotNull(servletMountSpec, "servletMountSpec");
            return this;
        }

        @Override
        public AuthenticatedHttpServerModule build() {
            return new AuthenticatedHttpServerModule(userTokenAuthoriserSpec, listenPortSpec, servletMountSpec);
        }
    }
}
