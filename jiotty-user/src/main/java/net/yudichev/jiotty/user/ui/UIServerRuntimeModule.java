package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.inject.Key;
import com.google.inject.multibindings.Multibinder;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;

/// Generic, reusable module that exposes [UIServerRuntime] — the pure request dispatcher — over a set of contributed [ApiPathHandler]s.
///
/// Each handler is supplied via [Builder#addApiPathHandler].
///
/// Callers that need UI-component registration ([UIServer]) on top of dispatch use [UIServerModule], which layers the registries, executor and built-in
/// handlers on this module.
public final class UIServerRuntimeModule extends BaseLifecycleComponentModule {
    private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs;

    private UIServerRuntimeModule(List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs) {
        this.apiPathHandlerSpecs = ImmutableList.copyOf(apiPathHandlerSpecs);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        Multibinder<ApiPathHandler> handlerBinder = Multibinder.newSetBinder(binder(), ApiPathHandler.class);
        for (BindingSpec<ApiPathHandler> spec : apiPathHandlerSpecs) {
            var handlerAnnotation = uniqueAnnotation();
            spec.bind(ApiPathHandler.class)
                .annotatedWith(handlerAnnotation)
                .installedBy(this::installLifecycleComponentModule);
            handlerBinder.addBinding().to(Key.get(ApiPathHandler.class, handlerAnnotation));
        }

        bind(UIServerRuntime.class).to(registerLifecycleComponent(UIServerRuntimeImpl.class));
        expose(UIServerRuntime.class);
    }

    public static final class Builder implements TypedBuilder<UIServerRuntimeModule> {
        private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs = new ArrayList<>();

        private Builder() {
        }

        /// Register an additional handler.
        public Builder addApiPathHandler(BindingSpec<ApiPathHandler> apiPathHandlerSpec) {
            apiPathHandlerSpecs.add(checkNotNull(apiPathHandlerSpec));
            return this;
        }

        @Override
        public UIServerRuntimeModule build() {
            return new UIServerRuntimeModule(apiPathHandlerSpecs);
        }
    }
}
