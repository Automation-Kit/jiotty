package net.yudichev.jiotty.common.app;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.jul.Log4jBridgeHandler;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

public final class Application {
    private static final Logger logger = LogManager.getLogger(Application.class);

    static {
        // Log4jBridgeHandler maps JUL CONFIG to Log4j DEBUG by default, matching the previous custom SLF4JBridgeHandler behaviour
        Log4jBridgeHandler.install(true, "", true);
        java.util.logging.Logger.getLogger("").setLevel(Level.FINEST);
    }

    private final List<LifecycleComponent> componentsAttemptedToStart = new CopyOnWriteArrayList<>();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private final AtomicBoolean jvmShuttingDown = new AtomicBoolean();
    private final AtomicBoolean startedAllComponentsSuccessfully = new AtomicBoolean();
    private final AtomicBoolean runCalled = new AtomicBoolean();
    private final AtomicReference<Injector> injectorRef = new AtomicReference<>();
    private final @Nullable Injector parentInjector;
    private final SpecifiedAnnotation specifiedAnnotation;
    private final Supplier<Module> moduleSupplier;
    private final ApplicationLifecycleControl applicationLifecycleControl;

    private CountDownLatch shutdownLatch;
    private Thread runThread;

    private Application(@Nullable Injector parentInjector, SpecifiedAnnotation specifiedAnnotation, Supplier<Module> moduleSupplier) {
        this.parentInjector = parentInjector;
        this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
        this.moduleSupplier = moduleSupplier;
        applicationLifecycleControl = new ApplicationLifecycleControl() {
            @Override
            public void initiateShutdown() {
                logger.info("Application requested shutdown");
                initiateStop();
            }

            @Override
            public void initiateRestart() {
                if (restarting.compareAndSet(false, true)) {
                    checkState(!jvmShuttingDown.get(), "Cannot initiate restart while JVM is shutting down");
                    logger.info("Application requested restart");
                    initiateStop();
                } else {
                    logger.info("Ignoring restart request - application restart already in progress");
                }
            }

            @Override
            public boolean restarting() {
                return restarting.get();
            }
        };
    }

    /// Start all [LifecycleComponent]s.
    ///
    /// @throws InterruptedException if the thread was interrupted while starting
    /// @throws RuntimeException     if one of the components failed to start; note components that are already started won't be stopped, use [#stop()] for
    /// that.
    public void start() throws InterruptedException {
        startedAllComponentsSuccessfully.set(false);
        componentsAttemptedToStart.clear();
        logger.info("Creating injector");
        var applicationSupportModule = new ApplicationSupportModule(specifiedAnnotation, applicationLifecycleControl);
        var mainModule = moduleSupplier.get();
        Injector injector = parentInjector == null ? Guice.createInjector(applicationSupportModule, mainModule)
                                                   : parentInjector.createChildInjector(applicationSupportModule, mainModule);
        injectorRef.set(injector);
        logger.info("Initialising components");
        try {
            List<LifecycleComponent> allComponents = injector
                    .findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                    .stream()
                    .map(lifecycleComponentBinding -> lifecycleComponentBinding.getProvider().get())
                    .collect(toImmutableList());

            logger.info("Starting components");
            for (LifecycleComponent component : allComponents) {
                if (Thread.interrupted()) {
                    throw new InterruptedException(String.format("Interrupted while starting; components attempted to start: %s out of %s",
                                                                 componentsAttemptedToStart.size(), allComponents.size()));
                }
                componentsAttemptedToStart.add(component);
                start(component);
            }

            startedAllComponentsSuccessfully.set(true);
            logger.info("Started");
        } catch (RuntimeException e) {
            injectorRef.set(null);
            throw e;
        }
    }

    /// @return `null` when the application is not started
    public @Nullable Injector getInjector() {
        return injectorRef.get();
    }

    /// Stop all components that have been started - must be called on same thread that called [#start()]. Attempts to stop all components regardless of any
    /// failures. Never throws exceptions.
    public void stop() {
        logger.info("Shutting down");
        stop(componentsAttemptedToStart);
        componentsAttemptedToStart.clear();
        logger.info("Shut down");
    }

    /// Run as a daemon: start and blocks until application initiates shutdown (or JVM is requested to shut down) and all components are stopped.
    public void run() {
        checkState(runCalled.compareAndSet(false, true), "Application.run() can only be called once");
        runThread = Thread.currentThread();

        AtomicReference<CountDownLatch> fullyStoppedLatchRef = new AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jvmShuttingDown.set(true);
            CountDownLatch fullyStoppedLatch = fullyStoppedLatchRef.get();
            if (fullyStoppedLatch != null && fullyStoppedLatch.getCount() > 0) {
                logger.info("Shutdown hook fired");
                initiateStop();
                MoreThrowables.asUnchecked(() -> {
                    if (!fullyStoppedLatch.await(1, TimeUnit.MINUTES)) {
                        logger.warn("Timed out waiting for partially initialised application to shut down");
                    }
                });
            }
        }));

        do {
            logger.info("Starting");

            shutdownLatch = new CountDownLatch(1);
            CountDownLatch fullyStoppedLatch = new CountDownLatch(1);
            fullyStoppedLatchRef.set(fullyStoppedLatch);

            try {
                start();
            } catch (InterruptedException | RuntimeException e) {
                logger.error("Unable to initialize", e);
                shutdownLatch.countDown();
                // intentionally clearing the interrupted flag to guarantee the immediately following latch await not to fail
                //noinspection ResultOfMethodCallIgnored
                Thread.interrupted();
            }

            MoreThrowables.asUnchecked(shutdownLatch::await);
            stop();

            fullyStoppedLatch.countDown();
        } while (restarting.getAndSet(false));
    }

    public static Builder builder() {
        return new Builder();
    }

    private void initiateStop() {
        if (startedAllComponentsSuccessfully.get()) {
            shutdownLatch.countDown();
        } else {
            logger.info("Interrupting startup sequence");
            runThread.interrupt();
        }
    }

    private static void start(LifecycleComponent lifecycleComponent) {
        logger.info("Starting component {}", lifecycleComponent.name());
        lifecycleComponent.start();
        logger.info("Started component {}", lifecycleComponent.name());
    }

    private static void stop(List<LifecycleComponent> lifecycleComponents) {
        Lists.reverse(lifecycleComponents).forEach(lifecycleComponent -> {
            try {
                logger.info("Stopping component {}", lifecycleComponent.name());
                lifecycleComponent.stop();
                logger.info("Stopped component {}", lifecycleComponent.name());
            } catch (Throwable e) {
                logger.error("Failed stopping component {}", lifecycleComponent.name(), e);
            }
        });
    }

    public static final class Builder implements TypedBuilder<Application>, HasWithAnnotation {
        private final ImmutableList.Builder<Supplier<Module>> moduleSupplierListBuilder = ImmutableList.builder();
        private Injector parentInjector;
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();

        public Builder addModule(Supplier<Module> moduleSupplier) {
            moduleSupplierListBuilder.add(moduleSupplier);
            return this;
        }

        public Builder withParentInjector(Injector parentInjector) {
            this.parentInjector = checkNotNull(parentInjector);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public Application build() {
            List<Supplier<Module>> moduleSuppliers = moduleSupplierListBuilder.build();
            Module module = new AbstractModule() {
                @Override
                protected void configure() {
                    moduleSuppliers.stream()
                                   .map(Supplier::get)
                                   .forEach(this::install);
                }
            };
            return new Application(parentInjector, specifiedAnnotation, () -> module);
        }
    }
}
