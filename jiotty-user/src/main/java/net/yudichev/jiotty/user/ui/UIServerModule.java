package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;

/// Exposes [UIServer] for the app to register UI components and [UIServerRuntime] for the HTTP server to handle requests and streams against those components.
public final class UIServerModule extends BaseLifecycleComponentModule {
    @Override
    protected void configure() {
        bind(OptionPersistence.class).to(OptionPersistenceImpl.class);
        registerLifecycleComponent(UIServerImpl.class);
        bind(UIServer.class).to(UIServerImpl.class);
        expose(UIServer.class);
        bind(UIServerRuntime.class).to(UIServerImpl.class);
        expose(UIServerRuntime.class);
    }
}
