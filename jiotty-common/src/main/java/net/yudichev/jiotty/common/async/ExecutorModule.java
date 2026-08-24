package net.yudichev.jiotty.common.async;

import com.google.inject.Singleton;
import com.google.inject.multibindings.OptionalBinder;
import io.micrometer.core.instrument.MeterRegistry;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import static net.yudichev.jiotty.common.inject.BaseModuleBuilder.simpleBuilder;

/// In addition to the [ExecutorFactory], this module also exposes a [TaskFailureReporter].
public final class ExecutorModule extends BaseExposedKeyModule<ExecutorFactory> {
    private ExecutorModule(SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
    }

    public static BaseModuleBuilder<ExecutorFactory, ?> builder() {
        return simpleBuilder(ExecutorModule::new);
    }

    @Override
    protected void configure() {
        // Scoped singleton so the factory and the exposed registry resolve to the same instance.
        bind(ListenerBackedTaskExceptionHandlerRegistry.class).in(Singleton.class);
        bind(TaskExceptionHandlerRegistry.class).to(ListenerBackedTaskExceptionHandlerRegistry.class);
        expose(TaskExceptionHandlerRegistry.class);
        bind(TaskFailureReporter.class).to(ListenerBackedTaskExceptionHandlerRegistry.class);
        expose(TaskFailureReporter.class);
        // Optional so executors are created (unmetered) where no metrics module is installed; present it as Optional<MeterRegistry> to the factory.
        OptionalBinder.newOptionalBinder(binder(), MeterRegistry.class);
        bind(exposedKey).to(ExecutorFactoryImpl.class).in(Singleton.class);
        expose(exposedKey);
    }
}
