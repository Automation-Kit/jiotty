package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.OptionPersistenceImpl;

import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

/// Exposes [UIServer] for the app to register UI components and [UIServerRuntime] for the HTTP server to handle requests and streams against those components.
public final class UIServerModule extends BaseLifecycleComponentModule {
    private final BindingSpec<String> threadNameSuffixSpec;
    private final BindingSpec<Duration> optionsStabilisationDelaySpec;

    private UIServerModule(BindingSpec<String> threadNameSuffixSpec, BindingSpec<Duration> optionsStabilisationDelaySpec) {
        this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
        this.optionsStabilisationDelaySpec = checkNotNull(optionsStabilisationDelaySpec);
    }

    @Override
    protected void configure() {
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);

        threadNameSuffixSpec.bind(String.class).annotatedWith(UIServerImpl.ThreadSuffix.class).installedBy(this::installLifecycleComponentModule);
        optionsStabilisationDelaySpec.bind(Duration.class)
                                     .annotatedWith(UIServerImpl.OptionsStabilisationDelay.class)
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
        private BindingSpec<Duration> optionsStabilisationDelaySpec = literally(Duration.ofMillis(100));

        private Builder() {
        }

        public Builder withThreadNameSuffix(BindingSpec<String> threadNameSuffixSpec) {
            this.threadNameSuffixSpec = checkNotNull(threadNameSuffixSpec);
            return this;
        }

        public Builder withOptionsStabilisationDelay(BindingSpec<Duration> optionsStabilisationDelaySpec) {
            this.optionsStabilisationDelaySpec = checkNotNull(optionsStabilisationDelaySpec);
            return this;
        }

        @Override
        public UIServerModule build() {
            return new UIServerModule(threadNameSuffixSpec, optionsStabilisationDelaySpec);
        }
    }
}
