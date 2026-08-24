package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.ExecutorProviderModule;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.user.push.PushDeviceModule;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.OptionPersistenceImpl;
import net.yudichev.jiotty.user.ui.sse.SseChannel;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.annotatedWith;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

/// Exposes [UIServer] for the app to register UI components and [UIServerRuntime] for the HTTP server to handle requests and streams against those components.
///
/// All [UIServer]-scoped sub-components share one per-instance [SchedulingExecutor] bound under [UIExecutor] so their state mutations are serialised through a
/// single thread.
///
/// External [ApiPathHandler] contributors plug in via [Builder#addApiPathHandler] — each call accepts a [BindingSpec] that resolves to a contributed handler.
/// Built-in handlers (`/options`, `/displayables`, `/displayables/item`, `/displayables/stream`, `/displayables/download`, `/push/devices`) are wired
/// internally and are not contributed by callers.
///
/// The [AdminAlertService] supplied via [Builder#setAdminAlertService] is required and is consumed by the built-in handlers to surface internal failures as
/// admin alerts (rather than only logging them).
public final class UIServerModule extends BaseLifecycleComponentModule {
    private final BindingSpec<String> threadNameSuffixSpec;
    private final BindingSpec<Duration> optionsThrottlingPeriodSpec;
    private final BindingSpec<VarStore> varStoreSpec;
    private final BindingSpec<AdminAlertService> adminAlertServiceSpec;
    private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs;

    private UIServerModule(BindingSpec<String> threadNameSuffixSpec,
                           BindingSpec<Duration> optionsThrottlingPeriodSpec,
                           BindingSpec<VarStore> varStoreSpec,
                           BindingSpec<AdminAlertService> adminAlertServiceSpec,
                           List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs) {
        this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
        this.optionsThrottlingPeriodSpec = checkNotNull(optionsThrottlingPeriodSpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.adminAlertServiceSpec = checkNotNull(adminAlertServiceSpec);
        this.apiPathHandlerSpecs = ImmutableList.copyOf(apiPathHandlerSpecs);
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(new TypeLiteral<>() {}).annotatedWith(OptionPersistenceImpl.Dependency.class).installedBy(this::installLifecycleComponentModule);
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);

        // The per-user suffix already names the UI executor's thread; bound here as well so a component logging from a container thread — where the thread
        // name carries no subject — can still say which user's stream it is talking about.
        threadNameSuffixSpec.bind(String.class)
                            .annotatedWith(SubjectId.class)
                            .installedBy(this::installLifecycleComponentModule);

        adminAlertServiceSpec.bind(AdminAlertService.class)
                             .annotatedWith(Dependency.class)
                             .installedBy(this::installLifecycleComponentModule);
        bind(AdminAlertService.class).annotatedWith(SseChannel.Dependency.class).to(Key.get(AdminAlertService.class, Dependency.class));
        install(new FactoryModuleBuilder().build(SseChannel.Factory.class));

        // Registered ahead of every component that resolves the executor in its doStart(): lifecycle components start in registration order, and
        // ExecutorProvider supplies the executor only once it has started itself.
        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(threadNameSuffixSpec.map(TypeToken.of(String.class),
                                                                                                      TypeToken.of(String.class),
                                                                                                      UIServerModule::executorThreadName))
                                                              .withFamily(literally("ui"))
                                                              .withAnnotation(forAnnotation(UIExecutor.class))
                                                              .build());

        installLifecycleComponentModule(PushDeviceModule.builder()
                                                        .withVarStore(varStoreSpec)
                                                        .withExecutor(annotatedWith(UIExecutor.class))
                                                        .build());
        expose(PushDeviceStore.class);

        optionsThrottlingPeriodSpec.bind(Duration.class)
                                   .annotatedWith(SseServiceImpl.OptionsThrottlingPeriod.class)
                                   .installedBy(this::installLifecycleComponentModule);

        bind(OptionRegistry.class).to(registerLifecycleComponent(OptionRegistryImpl.class));
        expose(OptionRegistry.class);
        bind(DisplayableRegistry.class).to(registerLifecycleComponent(DisplayableRegistryImpl.class));
        bind(SseService.class).to(registerLifecycleComponent(SseServiceImpl.class));

        bind(UIServer.class).to(UIServerImpl.class).in(Singleton.class);
        expose(UIServer.class);

        // Built-in handlers are constructed in THIS module's scope so they see the registries/executor/SseService/PushDeviceStore/@Dependency bound above, then
        // contributed to the runtime module as bound-to specs alongside the caller's external handler specs.
        UIServerRuntimeModule.Builder runtimeModuleBuilder = UIServerRuntimeModule.builder();
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(registerLifecycleComponent(OptionsPostHandler.class)));
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(Key.get(GetDisplayablesListHandler.class)));
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(registerLifecycleComponent(GetDisplayableItemHandler.class)));
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(registerLifecycleComponent(DisplayableDownloadHandler.class)));
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(Key.get(DisplayablesSseHandler.class)));
        runtimeModuleBuilder.addApiPathHandler(boundToBuiltInHandler(registerLifecycleComponent(PushDevicesHandler.class)));
        apiPathHandlerSpecs.forEach(runtimeModuleBuilder::addApiPathHandler);

        installLifecycleComponentModule(runtimeModuleBuilder.build());
        expose(UIServerRuntime.class);
    }

    /// Binds [ApiPathHandler] under a unique annotation in this module's scope to the given built-in handler key and returns a [BindingSpec] resolving to it,
    /// so the runtime module can gather it via [UIServerRuntimeModule.Builder#addApiPathHandler] while the handler stays constructed in this scope.
    private BindingSpec<ApiPathHandler> boundToBuiltInHandler(Key<? extends ApiPathHandler> handlerKey) {
        Annotation handlerAnnotation = uniqueAnnotation();
        Key<ApiPathHandler> annotatedKey = Key.get(ApiPathHandler.class, handlerAnnotation);
        bind(annotatedKey).to(handlerKey);
        return boundTo(annotatedKey);
    }

    private static String executorThreadName(String suffix) {
        return "UI" + (suffix.isBlank() ? "" : '-' + suffix);
    }

    public static Builder builder() {
        return new Builder();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    /// Identifies whose UI this server serves.
    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface SubjectId {
    }

    public static final class Builder implements TypedBuilder<UIServerModule> {
        private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs = new ArrayList<>();
        private BindingSpec<String> threadNameSuffixSpec = literally("");
        private BindingSpec<Duration> optionsThrottlingPeriodSpec = literally(Duration.ofMillis(500));
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);
        private BindingSpec<AdminAlertService> adminAlertServiceSpec;

        private Builder() {
        }

        public Builder setAdminAlertService(BindingSpec<AdminAlertService> adminAlertServiceSpec) {
            this.adminAlertServiceSpec = checkNotNull(adminAlertServiceSpec);
            return this;
        }

        public Builder withThreadNameSuffix(BindingSpec<String> threadNameSuffixSpec) {
            this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
            return this;
        }

        public Builder withOptionsThrottlingPeriod(BindingSpec<Duration> optionsThrottlingPeriodSpec) {
            this.optionsThrottlingPeriodSpec = checkNotNull(optionsThrottlingPeriodSpec);
            return this;
        }

        public Builder withVarStore(BindingSpec<VarStore> varStoreSpec) {
            this.varStoreSpec = checkNotNull(varStoreSpec);
            return this;
        }

        /// Register an additional handler.
        public Builder addApiPathHandler(BindingSpec<ApiPathHandler> apiPathHandlerSpec) {
            apiPathHandlerSpecs.add(checkNotNull(apiPathHandlerSpec));
            return this;
        }

        @Override
        public UIServerModule build() {
            return new UIServerModule(threadNameSuffixSpec,
                                      optionsThrottlingPeriodSpec,
                                      varStoreSpec,
                                      adminAlertServiceSpec,
                                      apiPathHandlerSpecs);
        }
    }
}
