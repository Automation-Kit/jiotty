package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.Multibinder;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
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
public final class UIServerModule extends BaseLifecycleComponentModule {
    private final BindingSpec<String> threadNameSuffixSpec;
    private final BindingSpec<Duration> optionsThrottlingPeriodSpec;
    private final BindingSpec<VarStore> varStoreSpec;
    private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs;

    private UIServerModule(BindingSpec<String> threadNameSuffixSpec,
                           BindingSpec<Duration> optionsThrottlingPeriodSpec,
                           BindingSpec<VarStore> varStoreSpec,
                           List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs) {
        this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
        this.optionsThrottlingPeriodSpec = checkNotNull(optionsThrottlingPeriodSpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
        this.apiPathHandlerSpecs = ImmutableList.copyOf(apiPathHandlerSpecs);
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(new TypeLiteral<>() {}).annotatedWith(OptionPersistenceImpl.Dependency.class).installedBy(this::installLifecycleComponentModule);
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);

        installLifecycleComponentModule(PushDeviceModule.builder()
                                                        .withVarStore(varStoreSpec)
                                                        .build());
        expose(PushDeviceStore.class);

        installLifecycleComponentModule(ExecutorProviderModule.builder()
                                                              .setThreadName(threadNameSuffixSpec.map(TypeToken.of(String.class),
                                                                                                      TypeToken.of(String.class),
                                                                                                      UIServerModule::executorThreadName))
                                                              .withAnnotation(forAnnotation(UIExecutor.class))
                                                              .build());

        optionsThrottlingPeriodSpec.bind(Duration.class)
                                   .annotatedWith(SseServiceImpl.OptionsThrottlingPeriod.class)
                                   .installedBy(this::installLifecycleComponentModule);

        bind(OptionRegistry.class).to(registerLifecycleComponent(OptionRegistryImpl.class));
        bind(DisplayableRegistry.class).to(registerLifecycleComponent(DisplayableRegistryImpl.class));
        bind(SseService.class).to(registerLifecycleComponent(SseServiceImpl.class));

        Multibinder<ApiPathHandler> handlerBinder = Multibinder.newSetBinder(binder(), ApiPathHandler.class);
        handlerBinder.addBinding().to(registerLifecycleComponent(OptionsPostHandler.class));
        handlerBinder.addBinding().to(GetDisplayablesListHandler.class);
        handlerBinder.addBinding().to(registerLifecycleComponent(GetDisplayableItemHandler.class));
        handlerBinder.addBinding().to(registerLifecycleComponent(DisplayableDownloadHandler.class));
        handlerBinder.addBinding().to(DisplayablesSseHandler.class);
        handlerBinder.addBinding().to(registerLifecycleComponent(PushDevicesHandler.class));

        // External contributors: each spec is bound inside this private module under a unique annotation, then plugged into the same multibinder.
        for (BindingSpec<ApiPathHandler> spec : apiPathHandlerSpecs) {
            var handlerAnnotation = uniqueAnnotation();
            spec.bind(ApiPathHandler.class)
                .annotatedWith(handlerAnnotation)
                .installedBy(this::installLifecycleComponentModule);
            handlerBinder.addBinding().to(Key.get(ApiPathHandler.class, handlerAnnotation));
        }

        bind(UIServer.class).to(registerLifecycleComponent(UIServerImpl.class));
        expose(UIServer.class);
        bind(UIServerRuntime.class).to(UIServerImpl.class);
        expose(UIServerRuntime.class);
    }

    private static String executorThreadName(String suffix) {
        return "UI" + (suffix.isBlank() ? "" : '-' + suffix);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<UIServerModule> {
        private final List<BindingSpec<ApiPathHandler>> apiPathHandlerSpecs = new ArrayList<>();
        private BindingSpec<String> threadNameSuffixSpec = literally("");
        private BindingSpec<Duration> optionsThrottlingPeriodSpec = literally(Duration.ofMillis(500));
        private BindingSpec<VarStore> varStoreSpec = boundTo(VarStore.class);

        private Builder() {
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
            return new UIServerModule(threadNameSuffixSpec, optionsThrottlingPeriodSpec, varStoreSpec, apiPathHandlerSpecs);
        }
    }
}
