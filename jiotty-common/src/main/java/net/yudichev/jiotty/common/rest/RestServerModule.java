package net.yudichev.jiotty.common.rest;

import com.google.common.reflect.TypeToken;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class RestServerModule extends BaseExposedKeyModule<RestServer> {
    private final BindingSpec<Integer> listenPortSpec;
    private final BindingSpec<Optional<String>> listenHostSpec;

    private RestServerModule(SpecifiedAnnotation specifiedAnnotation, BindingSpec<Integer> listenPortSpec, BindingSpec<Optional<String>> listenHostSpec) {
        super(specifiedAnnotation);
        this.listenPortSpec = checkNotNull(listenPortSpec);
        this.listenHostSpec = checkNotNull(listenHostSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        listenPortSpec.bind(int.class)
                      .annotatedWith(JavalinRestServer.ListenPort.class)
                      .installedBy(this::installLifecycleComponentModule);
        listenHostSpec.bind(new TypeLiteral<>() {})
                      .annotatedWith(JavalinRestServer.ListenHost.class)
                      .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(JavalinRestServer.class));
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<RestServer, Builder> {
        private BindingSpec<Integer> listenPortSpec = literally(0);
        /// The wildcard address on every protocol the host supports, which is what a server published to other hosts needs.
        private BindingSpec<Optional<String>> listenHostSpec = literally(Optional.empty());

        public Builder withListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec);
            return this;
        }

        /// Narrows the address the server binds to — `127.0.0.1` makes it reachable only from the machine it runs on.
        public Builder withListenHost(BindingSpec<String> listenHostSpec) {
            this.listenHostSpec = checkNotNull(listenHostSpec).map(new TypeToken<>() {}, new TypeToken<>() {}, Optional::of);
            return this;
        }

        @Override
        public ExposedKeyModule<RestServer> build() {
            return new RestServerModule(specifiedAnnotation(), listenPortSpec, listenHostSpec);
        }
    }
}
