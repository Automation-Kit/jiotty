package net.yudichev.jiotty.user.ui;

import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.OptionPersistenceImpl;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Exposes [UIServer] for the app to register UI components and [UIServerRuntime] for the HTTP server to handle requests and streams against those components.
public final class UIServerModule extends BaseLifecycleComponentModule {
    private final BindingSpec<String> threadNameSuffixSpec;
    private final BindingSpec<Duration> optionsThrottlingPeriodSpec;
    private final BindingSpec<VarStore> varStoreSpec;

    private UIServerModule(BindingSpec<String> threadNameSuffixSpec, BindingSpec<Duration> optionsThrottlingPeriodSpec, BindingSpec<VarStore> varStoreSpec) {
        this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
        this.optionsThrottlingPeriodSpec = checkNotNull(optionsThrottlingPeriodSpec);
        this.varStoreSpec = checkNotNull(varStoreSpec);
    }

    @Override
    protected void configure() {
        varStoreSpec.bind(new TypeLiteral<>() {}).annotatedWith(OptionPersistenceImpl.Dependency.class).installedBy(this::installLifecycleComponentModule);
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);

        threadNameSuffixSpec.bind(String.class).annotatedWith(UIServerImpl.ThreadSuffix.class).installedBy(this::installLifecycleComponentModule);
        optionsThrottlingPeriodSpec.bind(Duration.class)
                                   .annotatedWith(UIServerImpl.OptionsThrottlingPeriod.class)
                                   .installedBy(this::installLifecycleComponentModule);
        registerLifecycleComponent(UIServerImpl.class);
        bind(UIServer.class).to(UIServerImpl.class);
        expose(UIServer.class);
        bind(UIServerRuntime.class).to(UIServerImpl.class);
        expose(UIServerRuntime.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<UIServerModule> {
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

        @Override
        public UIServerModule build() {
            return new UIServerModule(threadNameSuffixSpec, optionsThrottlingPeriodSpec, varStoreSpec);
        }
    }
}
