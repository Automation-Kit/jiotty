package net.yudichev.jiotty.common.async;

import com.google.inject.Singleton;
import com.google.inject.assistedinject.FactoryModuleBuilder;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;

public final class ExecutorModule extends BaseLifecycleComponentModule implements ExposedKeyModule<ExecutorFactory> {
    @Override
    protected void configure() {
        // Scoped singleton so the executor (which injects the concrete type) and the exposed registry resolve to the same instance.
        bind(ListenerBackedTaskExceptionHandlerRegistry.class).in(Singleton.class);
        bind(TaskExceptionHandlerRegistry.class).to(ListenerBackedTaskExceptionHandlerRegistry.class);
        expose(TaskExceptionHandlerRegistry.class);
        install(new FactoryModuleBuilder()
                        .implement(SchedulingExecutor.class, SingleThreadedSchedulingExecutor.class)
                        .build(getExposedKey()));
        expose(getExposedKey());
    }
}
