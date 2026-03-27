package net.yudichev.jiotty.common.inject;

import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.PrivateModule;
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;

import java.util.LinkedHashSet;
import java.util.Set;

public abstract class BaseLifecycleComponentModule extends PrivateModule {
    private final Set<Key<? extends LifecycleComponent>> lifecycleComponentKeys = new LinkedHashSet<>();

    protected final void installLifecycleComponentModule(Module module) {
        if (module instanceof BaseLifecycleComponentModule lifecycleComponentModule) {
            install(module);
            lifecycleComponentModule.lifecycleComponentKeys.forEach(this::exposeKey);
        } else {
            install(module);
        }
    }

    /// Bind the target lifecycle component as a singleton and register it.
    protected final <T extends LifecycleComponent> Key<T> registerLifecycleComponent(Class<T> implClass) {
        return registerLifecycleComponent(Key.get(implClass));
    }

    /// Bind the target lifecycle component as a singleton and register it.
    protected final <T extends LifecycleComponent> Key<T> registerLifecycleComponent(TypeLiteral<T> implType) {
        return registerLifecycleComponent(Key.get(implType));
    }

    /// Bind the target lifecycle component as a singleton and register it.
    protected final <T extends LifecycleComponent> Key<T> registerLifecycleComponent(Key<T> implKey) {
        bind(implKey).in(Singleton.class);
        Key<LifecycleComponent> lifecycleComponentKey = Key.get(LifecycleComponent.class, GuiceUtil.uniqueAnnotation());
        bind(lifecycleComponentKey).to(implKey);
        exposeKey(lifecycleComponentKey);
        return implKey;
    }

    /// Register the lifecycle component, that was already bound as a singleton.
    protected final <T extends LifecycleComponent> void registerBoundLifecycleComponent(Class<T> implClass) {
        Key<LifecycleComponent> lifecycleComponentKey = Key.get(LifecycleComponent.class, GuiceUtil.uniqueAnnotation());
        bind(lifecycleComponentKey).to(implClass);
        exposeKey(lifecycleComponentKey);
    }

    private void exposeKey(Key<? extends LifecycleComponent> key) {
        expose(key);
        lifecycleComponentKeys.add(key);
    }
}
