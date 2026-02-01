package net.yudichev.jiotty.common.app;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import org.slf4j.spi.LocationAwareLogger;

import java.text.MessageFormat;
import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;

public final class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    static {
        SLF4JBridgeHandler.removeHandlersForRootLogger();

        // copy-paste from source class with one tiny change: CONFIG is also included in DEBUG because many google HTTP classes use it to log
        // all sent and received messages
        LogManager.getLogManager().getLogger("").addHandler(new SLF4JBridgeHandler() {
            private final String FQCN = java.util.logging.Logger.class.getName();

            @Override
            protected void callLocationAwareLogger(LocationAwareLogger lal, LogRecord record) {
                int julLevelValue = record.getLevel().intValue();
                int slf4jLevel;

                if (julLevelValue <= Level.FINEST.intValue()) {
                    slf4jLevel = LocationAwareLogger.TRACE_INT;
                } else if (julLevelValue <= Level.CONFIG.intValue()) { // THIS IS WHERE THE CHANGE IS
                    slf4jLevel = LocationAwareLogger.DEBUG_INT;
                } else if (julLevelValue <= Level.INFO.intValue()) {
                    slf4jLevel = LocationAwareLogger.INFO_INT;
                } else if (julLevelValue <= Level.WARNING.intValue()) {
                    slf4jLevel = LocationAwareLogger.WARN_INT;
                } else {
                    slf4jLevel = LocationAwareLogger.ERROR_INT;
                }
                String i18nMessage = getMessageI18N(record);
                lal.log(null, FQCN, slf4jLevel, i18nMessage, null, record.getThrown());
            }

            private static String getMessageI18N(LogRecord record) {
                String message = record.getMessage();

                if (message == null) {
                    return null;
                }

                ResourceBundle bundle = record.getResourceBundle();
                if (bundle != null) {
                    try {
                        message = bundle.getString(message);
                    } catch (MissingResourceException e) {
                    }
                }
                Object[] params = record.getParameters();
                // avoid formatting when there are no or 0 parameters. see also
                // http://jira.qos.ch/browse/SLF4J-203
                if (params != null && params.length > 0) {
                    try {
                        message = MessageFormat.format(message, params);
                    } catch (IllegalArgumentException e) {
                        // default to the same behavior as in java.util.logging.Formatter.formatMessage(LogRecord)
                        // see also http://jira.qos.ch/browse/SLF4J-337
                        return message;
                    }
                }
                return message;
            }
        });

        java.util.logging.Logger.getLogger("").setLevel(Level.FINEST);
    }

    private final List<LifecycleComponent> componentsAttemptedToStart = new CopyOnWriteArrayList<>();
    private final AtomicBoolean restarting = new AtomicBoolean();
    private final AtomicBoolean jvmShuttingDown = new AtomicBoolean();
    private final AtomicBoolean startedAllComponentsSuccessfully = new AtomicBoolean();
    private final AtomicBoolean runCalled = new AtomicBoolean();
    private final AtomicReference<Injector> injectorRef = new AtomicReference<>();
    private final Supplier<Module> moduleSupplier;
    private final ApplicationLifecycleControl applicationLifecycleControl;

    private CountDownLatch shutdownLatch;
    private Thread runThread;

    private Application(Supplier<Module> moduleSupplier) {
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
    ///                              that.
    public void start() throws InterruptedException {
        startedAllComponentsSuccessfully.set(false);
        componentsAttemptedToStart.clear();
        logger.info("Creating injector");
        Injector injector = Guice.createInjector(new ApplicationSupportModule(applicationLifecycleControl), moduleSupplier.get());
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

    /// Stop all components that have been started - must be called on same thread that called [#start()].
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

    public static final class Builder implements TypedBuilder<Application> {
        private final ImmutableList.Builder<Supplier<Module>> moduleSupplierListBuilder = ImmutableList.builder();

        public Builder addModule(Supplier<Module> moduleSupplier) {
            moduleSupplierListBuilder.add(moduleSupplier);
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
            return new Application(() -> module);
        }
    }
}
