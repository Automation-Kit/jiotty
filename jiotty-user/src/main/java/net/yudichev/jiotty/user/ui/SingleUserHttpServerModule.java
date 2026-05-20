package net.yudichev.jiotty.user.ui;

import com.google.inject.multibindings.Multibinder;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.user.ui.options.Option;

import static com.google.common.base.Preconditions.checkNotNull;

/// Installs the unauthenticated single-user HTTP server.
///
/// This is intended for trusted applications not exposed to internet, such as home automation projects. Such apps would typically use one global [UIServer]
/// instance for the whole process.
///
/// It also exposes publicly static resources for the basic web UI supporting [Option]s and [Displayable]s. This UI is accessible via `/ui/index.html`.
public final class SingleUserHttpServerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<UIServer> {
    private final BindingSpec<Integer> listenPortSpec;

    private SingleUserHttpServerModule(BindingSpec<Integer> listenPortSpec) {
        this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        listenPortSpec.bind(int.class).annotatedWith(UIHttpServerImpl.ListenPort.class).installedBy(this::installLifecycleComponentModule);
        bind(UIRequestAuthoriser.class).annotatedWith(ApiServletMount.Dependency.class).to(SingleUserUIRequestAuthoriser.class);
        Multibinder<ServletMount> mountBinder = Multibinder.newSetBinder(binder(), ServletMount.class);
        mountBinder.addBinding().to(ApiServletMount.class);
        mountBinder.addBinding().to(StaticResourceServletMount.class);
        registerLifecycleComponent(UIHttpServerImpl.class);
    }

    public static final class Builder implements TypedBuilder<SingleUserHttpServerModule> {
        private BindingSpec<Integer> listenPortSpec;

        public Builder setListenPort(BindingSpec<Integer> listenPortSpec) {
            this.listenPortSpec = checkNotNull(listenPortSpec, "listenPortSpec");
            return this;
        }

        @Override
        public SingleUserHttpServerModule build() {
            return new SingleUserHttpServerModule(listenPortSpec);
        }
    }
}
