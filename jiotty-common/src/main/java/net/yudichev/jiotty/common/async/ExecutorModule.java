package net.yudichev.jiotty.common.async;

import com.google.inject.Singleton;
import com.google.inject.multibindings.OptionalBinder;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;

public final class ExecutorModule extends BaseLifecycleComponentModule implements ExposedKeyModule<ExecutorFactory> {
    @Override
    protected void configure() {
        // Scoped singleton so the factory and the exposed registry resolve to the same instance.
        bind(ListenerBackedTaskExceptionHandlerRegistry.class).in(Singleton.class);
        bind(TaskExceptionHandlerRegistry.class).to(ListenerBackedTaskExceptionHandlerRegistry.class);
        expose(TaskExceptionHandlerRegistry.class);
        // Optional so executors are created (unmetered) where no metrics module is installed; present it as Optional<MeterRegistry> to the factory.
        OptionalBinder.newOptionalBinder(binder(), MeterRegistry.class);
        bind(getExposedKey()).to(ExecutorFactoryImpl.class).in(Singleton.class);
        expose(getExposedKey());
    }
}
