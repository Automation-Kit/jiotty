package net.yudichev.jiotty.logging.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;
import net.yudichev.jiotty.user.ui.UIServer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.boundTo;

public final class UILogLevelConfigurationModule extends BaseLifecycleComponentModule {
    private final BindingSpec<UIServer> uiServerSpec;
    private final BindingSpec<LoggingLevelConfigurator> loggingLevelConfiguratorSpec;

    private UILogLevelConfigurationModule(BindingSpec<UIServer> uiServerSpec,
                                          BindingSpec<LoggingLevelConfigurator> loggingLevelConfiguratorSpec) {
        this.uiServerSpec = checkNotNull(uiServerSpec);
        this.loggingLevelConfiguratorSpec = checkNotNull(loggingLevelConfiguratorSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        uiServerSpec.bind(UIServer.class)
                    .annotatedWith(UiLogLevelConfigurator.Dependency.class)
                    .installedBy(this::installLifecycleComponentModule);
        loggingLevelConfiguratorSpec.bind(LoggingLevelConfigurator.class)
                                    .annotatedWith(UiLogLevelConfigurator.Dependency.class)
                                    .installedBy(this::installLifecycleComponentModule);
        registerLifecycleComponent(UiLogLevelConfigurator.class);
    }

    public static class Builder implements TypedBuilder<UILogLevelConfigurationModule> {
        private BindingSpec<UIServer> uiServerSpec = boundTo(UIServer.class);
        private BindingSpec<LoggingLevelConfigurator> loggingLevelConfiguratorSpec = boundTo(LoggingLevelConfigurator.class);

        public Builder withUIServer(BindingSpec<UIServer> uiServerSpec) {
            this.uiServerSpec = checkNotNull(uiServerSpec);
            return this;
        }

        public Builder withLoggingLevelConfigurator(BindingSpec<LoggingLevelConfigurator> loggingLevelConfiguratorSpec) {
            this.loggingLevelConfiguratorSpec = checkNotNull(loggingLevelConfiguratorSpec);
            return this;
        }

        @Override
        public UILogLevelConfigurationModule build() {
            return new UILogLevelConfigurationModule(uiServerSpec, loggingLevelConfiguratorSpec);
        }
    }
}
