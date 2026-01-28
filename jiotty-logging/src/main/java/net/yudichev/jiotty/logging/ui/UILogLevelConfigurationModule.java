package net.yudichev.jiotty.logging.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.logging.PersistingLog4jLevelConfiguratorModule;

public final class UILogLevelConfigurationModule extends BaseLifecycleComponentModule {

    @Override
    protected void configure() {
        installLifecycleComponentModule(PersistingLog4jLevelConfiguratorModule.builder().build());
        registerLifecycleComponent(UiLogLevelConfigurator.class);
    }
}
