package net.yudichev.jiotty.common.rest;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class RestServerModule extends BaseExposedKeyModule<RestServer> {
    private final BindingSpec<Integer> listenPortSpec;

    private RestServerModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<Integer> listenPortSpec) {
        super(specifiedAnnotation);
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
        bind(exposedKey).to(registerLifecycleComponent(JavalinRestServer.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<RestServer, Builder> {
        private BindingSpec<Integer> listenPortSpec = literally(0);

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<RestServer> build() {
            return new RestServerModule(specifiedAnnotation(), listenPortSpec);
        }
    }
}
