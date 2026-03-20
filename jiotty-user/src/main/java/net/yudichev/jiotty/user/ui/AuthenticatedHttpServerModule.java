package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;

public final class AuthenticatedHttpServerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<UIHttpServer> {
    public static final String PATH_ROOT = "/ui";
    public static final String SUB_PATH_OPTIONS = "/options";

    private final BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
    private final BindingSpec<Integer> listenPortSpec;

    private AuthenticatedHttpServerModule(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec,
                                          BindingSpec<Integer> listenPortSpec) {
        this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
        this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
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
        bind(getExposedKey()).to(registerLifecycleComponent(UIHttpServerImpl.class));
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<AuthenticatedHttpServerModule> {
        private BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec;
        private BindingSpec<Integer> listenPortSpec = BindingSpec.literally(0);

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
            return this;
        }

        public Builder setUserTokenAuthoriser(BindingSpec<UserTokenAuthoriser> userTokenAuthoriserSpec) {
            this.userTokenAuthoriserSpec = checkNotNull(userTokenAuthoriserSpec, "userTokenAuthoriserSpec");
            return this;
        }

        @Override
        public AuthenticatedHttpServerModule build() {
            return new AuthenticatedHttpServerModule(userTokenAuthoriserSpec, listenPortSpec);
        }
    }
}
