package net.yudichev.jiotty.common.rest;

import com.google.inject.Module;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class RestServerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<RestServer> {
    private final BindingSpec<Integer> listenPortSpec;

    private RestServerModule(BindingSpec<Integer> listenPortSpec) {
        this.listenPortSpec = checkNotNull(listenPortSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        listenPortSpec.bind(int.class)
                      .annotatedWith(JavalinRestServer.ListenPort.class)
                      .installedBy(this::installLifecycleComponentModule);
        bind(getExposedKey()).to(registerLifecycleComponent(JavalinRestServer.class));
        expose(getExposedKey());
    }

    public static final class Builder implements TypedBuilder<Module> {
        private BindingSpec<Integer> listenPortSpec = literally(0);

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec);
            return this;
        }

        @Override
        public Module build() {
            return new RestServerModule(listenPortSpec);
        }
    }
}
