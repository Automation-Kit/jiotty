package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;

public final class UIServerModule extends BaseLifecycleComponentModule implements ExposedKeyModule<UIServer> {
    @Override
    protected void configure() {
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);
        bind(getExposedKey()).to(registerLifecycleComponent(UIServerImpl.class));
        expose(getExposedKey());
    }
}
