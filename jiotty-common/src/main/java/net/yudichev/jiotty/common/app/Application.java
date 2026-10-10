package net.yudichev.jiotty.common.app;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorOwner;
import net.yudichev.jiotty.common.async.QueueFullException;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.jul.Log4jBridgeHandler;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static net.yudichev.jiotty.common.lang.Locks.inLock;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// Starts and stops the [LifecycleComponent]s of a module graph as one unit, as many times as asked. [#start()], [#stop()], [#close()] and [#run()]
/// serialise on one lock, so a call from a second thread waits for the one in progress; [#getInjector()], and the [ApplicationLifecycleControl] and
/// [ExecutorOwner] each start binds, are safe from any thread and return at once whatever is in progress.
public final class Application extends BaseIdempotentCloseable implements StringFormattable {
    private static final Logger logger = LogManager.getLogger(Application.class);

    /// How long the JVM shutdown hook waits for [#run()] to finish stopping the components.
    private static final Duration JVM_SHUTDOWN_WAIT = Duration.ofMinutes(1);

    static {
        // Log4jBridgeHandler maps JUL CONFIG to Log4j DEBUG by default, matching the previous custom SLF4JBridgeHandler behaviour
        Log4jBridgeHandler.install(true, "", true);
        java.util.logging.Logger.getLogger("").setLevel(Level.FINEST);
    }

    private final String name;
    private final @Nullable Injector parentInjector;
    private final SpecifiedAnnotation specifiedAnnotation;
    private final Supplier<Module> moduleSupplier;
    private final @Nullable PanicHandler panicHandler;
    private final Consumer<Runnable> shutdownHookRegistrar;
    private final Checkpoints checkpoints;
    private final Object lifecycleLock = new Object();
    @GuardedBy("lifecycleLock")
    private final List<LifecycleComponent> componentsAttemptedToStart = new ArrayList<>();
    /// `null` until a start has started all its components, after a failed start and after a stop; written under [#lifecycleLock].
    private volatile @Nullable Injector injector;
    /// The current start, from [#start()] until [#stop()]; `null` in between.
    @GuardedBy("lifecycleLock")
    private @Nullable Incarnation incarnation;
    /// `null` until [#run()] is called.
    @GuardedBy("lifecycleLock")
    private @Nullable RunLoop runLoop;

    private Application(String name,
                        @Nullable Injector parentInjector,
                        SpecifiedAnnotation specifiedAnnotation,
                        Supplier<Module> moduleSupplier,
                        @Nullable PanicHandler panicHandler,
                        Consumer<Runnable> shutdownHookRegistrar,
                        Checkpoints checkpoints) {
        this.name = checkNotNull(name);
        this.parentInjector = parentInjector;
        this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
        this.moduleSupplier = moduleSupplier;
        this.panicHandler = panicHandler;
        this.shutdownHookRegistrar = checkNotNull(shutdownHookRegistrar);
        this.checkpoints = checkNotNull(checkpoints);
    }

    /// Start all [LifecycleComponent]s, in binding order. The [ApplicationLifecycleControl] this start binds, and the executors constructed on this thread
    /// during it, panic this start alone: see [Builder#withPanicHandler(PanicHandler)].
    ///
    /// @throws IllegalStateException if the application is started, or a failed start has not been followed by [#stop()]
    /// @throws InterruptedException  if the thread was interrupted while starting
    /// @throws RuntimeException      if one of the components failed to start; note components that are already started won't be stopped, use [#stop()] for
    ///                               that.
    public void start() throws InterruptedException {
        synchronized (lifecycleLock) {
            checkState(incarnation == null, "%s is already started", this);
            var newIncarnation = new Incarnation(runLoop);
            incarnation = newIncarnation;
            // Bound around injector creation too: singletons are built lazily, so an executor created in a constructor is created here as well.
            ScopedValue.where(ExecutorOwner.CURRENT, newIncarnation).call(() -> startComponents(newIncarnation));
        }
    }

    @GuardedBy("lifecycleLock")
    private @Nullable Void startComponents(Incarnation newIncarnation) throws InterruptedException {
        logger.info("[{}] Application Starting", name);
        logger.info("[{}] Creating injector", name);
        var applicationSupportModule = new ApplicationSupportModule(specifiedAnnotation, newIncarnation);
        var mainModule = moduleSupplier.get();
        Injector newInjector = parentInjector == null ? Guice.createInjector(applicationSupportModule, mainModule)
                                                      : parentInjector.createChildInjector(applicationSupportModule, mainModule);
        logger.info("[{}] Initialising components", name);
        List<LifecycleComponent> allComponents = newInjector
                .findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                .stream()
                .map(lifecycleComponentBinding -> lifecycleComponentBinding.getProvider().get())
                .collect(toImmutableList());

        logger.info("[{}] Starting components", name);
        for (LifecycleComponent component : allComponents) {
            if (Thread.interrupted()) {
                throw new InterruptedException(String.format("Interrupted while starting; components attempted to start: %s out of %s",
                                                             componentsAttemptedToStart.size(), allComponents.size()));
            }
            componentsAttemptedToStart.add(component);
            start(component);
        }
        injector = newInjector;
        logger.info("[{}] Application Started", name);
        return null;
    }

    /// @return the injector of the current start, or `null` until its components have all started, after a failed start and after [#stop()]
    public @Nullable Injector getInjector() {
        return injector;
    }

    /// Stops the components the current start started, in reverse order, going on past any that fails. A panic of that start arriving from here on is
    /// ignored.
    public void stop() {
        logger.info("[{}] Shutting down", name);
        synchronized (lifecycleLock) {
            if (incarnation != null) {
                incarnation.markStopped();
                incarnation = null;
            }
            injector = null;
            stop(componentsAttemptedToStart);
            componentsAttemptedToStart.clear();
        }
        logger.info("[{}] Shut down", name);
    }

    @Override
    protected void doClose() {
        stop();
    }

    /// Runs the application on this thread until it is shut down: starts it, waits for a shutdown or restart request, stops it, and starts it again on a
    /// restart. A shutdown is requested through the bound [ApplicationLifecycleControl], by a panic, or by the JVM shutting down, from any thread; one that
    /// arrives while the components are starting interrupts the start.
    ///
    /// @throws IllegalStateException if called a second time
    public void run() {
        var newRunLoop = new RunLoop();
        synchronized (lifecycleLock) {
            checkState(runLoop == null, "Application.run() can only be called once");
            runLoop = newRunLoop;
        }
        shutdownHookRegistrar.accept(newRunLoop::onJvmShutdown);

        newRunLoop.startBegins();
        do {
            assert checkpoints.beforeStart();
            boolean started = false;
            try {
                start();
                started = true;
            } catch (InterruptedException | RuntimeException e) {
                logger.error("[{}] Unable to initialize", name, e);
            } finally {
                newRunLoop.startEnded();
            }
            if (started) {
                newRunLoop.awaitRequest();
            }
            stop();
            assert checkpoints.afterStop();
        } while (newRunLoop.restartBegins());
        newRunLoop.finish();
    }

    public static Builder builder() {
        return new Builder();
    }

    private void start(LifecycleComponent lifecycleComponent) {
        logger.info("[{}] Starting component {}", name, lifecycleComponent.name());
        lifecycleComponent.start();
        logger.info("[{}] Started component {}", name, lifecycleComponent.name());
    }

    private void stop(List<LifecycleComponent> lifecycleComponents) {
        Lists.reverse(lifecycleComponents).forEach(lifecycleComponent -> {
            try {
                logger.info("[{}] Stopping component {}", name, lifecycleComponent.name());
                lifecycleComponent.stop();
                logger.info("[{}] Stopped component {}", name, lifecycleComponent.name());
            } catch (Throwable e) {
                logger.error("[{}] Failed stopping component {}", name, lifecycleComponent.name(), e);
            }
        });
    }

    @Override
    public String toString() {
        return toString(32);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "Application ");
        Append.to(appendable, name);
    }

    /// The requests [#run()] answers, made from any thread, and the run thread's progress through them. The run thread never holds [#lock] while starting
    /// or stopping a component.
    private final class RunLoop {
        private final Lock lock = new ReentrantLock();
        private final Condition stateChange = lock.newCondition();
        private final Thread runThread = Thread.currentThread();
        /// While the run thread is starting the components, so that a request interrupts it.
        @GuardedBy("lock")
        private boolean starting;
        /// Once set, the loop ends after the stop in progress or the next one, whatever restart is requested.
        @GuardedBy("lock")
        private boolean shutdownRequested;
        /// Cleared when the run thread acts on it.
        @GuardedBy("lock")
        private boolean restartRequested;
        @GuardedBy("lock")
        private boolean jvmShuttingDown;
        /// Set once [#run()] has stopped the components for the last time.
        @GuardedBy("lock")
        private boolean finished;

        void startBegins() {
            inLock(lock, () -> {
                starting = true;
            });
        }

        /// Clears an interrupt the start may have received, so that the components' stop does not inherit it.
        void startEnded() {
            inLock(lock, () -> {
                starting = false;
            });
            //noinspection ResultOfMethodCallIgnored clearing the flag is the point
            Thread.interrupted();
        }

        void awaitRequest() {
            inLock(lock, () -> {
                while (!shutdownRequested && !restartRequested) {
                    stateChange.awaitUninterruptibly();
                }
            });
        }

        /// @return whether the loop starts the components again: a restart was requested and no shutdown has been since
        boolean restartBegins() {
            return inLock(lock, () -> {
                boolean restart = restartRequested && !shutdownRequested;
                restartRequested = false;
                starting = restart;
                return restart;
            });
        }

        void finish() {
            inLock(lock, () -> {
                finished = true;
                stateChange.signalAll();
            });
        }

        void requestShutdown() {
            logger.info("[{}] Application requested shutdown", name);
            inLock(lock, () -> {
                shutdownRequested = true;
                wakeRunThread();
            });
        }

        /// The JVM shutdown hook: requests a shutdown and returns once the run has finished, or [#JVM_SHUTDOWN_WAIT] has passed.
        void onJvmShutdown() {
            boolean alreadyFinished = inLock(lock, () -> {
                jvmShuttingDown = true;
                shutdownRequested = true;
                if (!finished) {
                    wakeRunThread();
                }
                return finished;
            });
            if (alreadyFinished) {
                return;
            }
            logger.info("[{}] Shutdown hook fired", name);
            boolean timedOut = inLock(lock, () -> {
                assert checkpoints.afterJvmShutdownRequested();
                asUnchecked(() -> {
                    long remainingNanos = JVM_SHUTDOWN_WAIT.toNanos();
                    while (!finished && remainingNanos > 0) {
                        remainingNanos = stateChange.awaitNanos(remainingNanos);
                    }
                });
                return !finished;
            });
            if (timedOut) {
                logger.warn("[{}] Timed out waiting for partially initialised application to shut down", name);
            }
        }

        /// @throws IllegalStateException if the JVM is shutting down
        void requestRestart() {
            boolean accepted = inLock(lock, () -> {
                checkState(!jvmShuttingDown, "Cannot initiate restart while JVM is shutting down");
                if (restartRequested) {
                    return false;
                }
                restartRequested = true;
                assert checkpoints.afterRestartAccepted();
                wakeRunThread();
                return true;
            });
            if (accepted) {
                logger.info("[{}] Application requested restart", name);
            } else {
                logger.info("[{}] Ignoring restart request - application restart already in progress", name);
            }
        }

        boolean restarting() {
            return inLock(lock, () -> restartRequested);
        }

        /// Interrupts the run thread if it is starting the components, which [#run()] reports as the start's failure.
        @GuardedBy("lock")
        private void wakeRunThread() {
            if (starting) {
                runThread.interrupt();
            }
            stateChange.signalAll();
        }
    }

    /// One start of this application: the lifecycle control its components are given, and the owner of the executors constructed during it.
    private final class Incarnation implements ExecutorOwner, ApplicationLifecycleControl {
        /// `null` for a start driven by [Application#start()], which can be neither shut down nor restarted from here.
        private final @Nullable RunLoop runLoop;
        /// Guards the fields below, which the starting thread and any panicking thread both reach. It is held to read or flip them and to run the discarders,
        /// which [ExecutorOwner#addBacklogDiscarder(Runnable)] requires to neither block nor take a lock.
        private final Object lock = new Object();
        @GuardedBy("lock")
        private final List<Runnable> backlogDiscarders = new ArrayList<>();
        @GuardedBy("lock")
        private boolean panicked;
        @GuardedBy("lock")
        private boolean stopped;

        private Incarnation(@Nullable RunLoop runLoop) {
            this.runLoop = runLoop;
        }

        @Override
        public void initiateShutdown() {
            requireRunLoop().requestShutdown();
        }

        @Override
        public void initiateRestart() {
            requireRunLoop().requestRestart();
        }

        @Override
        public boolean restarting() {
            return runLoop != null && runLoop.restarting();
        }

        private RunLoop requireRunLoop() {
            checkState(runLoop != null, "Application %s is not driven by run(), so it cannot be shut down or restarted", name);
            return runLoop;
        }

        @Override
        public void addBacklogDiscarder(Runnable backlogDiscarder) {
            checkNotNull(backlogDiscarder);
            synchronized (lock) {
                backlogDiscarders.add(backlogDiscarder);
            }
        }

        @Override
        public void onQueueFull(String executorName, QueueFullException rejection) {
            handlePanic("executor " + executorName + " has a full queue", rejection, rejection::markOwnerPanicked);
        }

        @Override
        public void panic(String reason, Throwable cause) {
            handlePanic(reason, cause, () -> {});
        }

        /// Discards the backlog of every executor of this start and runs the panic handler, unless this start has already panicked or been stopped.
        ///
        /// @param onPanicked runs once this start counts as panicked over `cause`, now or earlier, before the panic handler
        /// @throws IllegalStateException if the application has no panic handler and is driven by [Application#start()]
        private void handlePanic(String reason, Throwable cause, Runnable onPanicked) {
            String ignoreReason;
            boolean panickedOverIt;
            int discardedBacklogCount = 0;
            synchronized (lock) {
                ignoreReason = stopped ? "stopped" : panicked ? "already panicked" : null;
                panickedOverIt = !stopped;
                if (ignoreReason == null) {
                    checkState(panicHandler != null || runLoop != null, "Application %s cannot handle a panic: it has no panic handler", name);
                    panicked = true;
                    // Under the lock, so that a stop beginning meanwhile queues its teardown after the discard.
                    backlogDiscarders.forEach(Runnable::run);
                    discardedBacklogCount = backlogDiscarders.size();
                }
            }
            if (panickedOverIt) {
                onPanicked.run();
            }
            if (ignoreReason != null) {
                logger.info("[{}] Ignoring a panic of a start that has {}: {}", name, ignoreReason, reason);
                return;
            }
            logger.info("[{}] Panic, discarded the queued tasks of {} executor(s): {}", name, discardedBacklogCount, reason, cause);
            // Outside the lock, being foreign code, so a stop can begin while it runs: a handler restarting a stopped app checks that app is still current.
            try {
                if (panicHandler != null) {
                    panicHandler.onPanic(reason, cause);
                }
            } finally {
                if (runLoop != null) {
                    runLoop.requestShutdown();
                }
            }
        }

        void markStopped() {
            synchronized (lock) {
                stopped = true;
            }
        }
    }

    public static final class Builder implements TypedBuilder<Application>, HasWithAnnotation {
        private final ImmutableList.Builder<Supplier<? extends Module>> moduleSupplierListBuilder = ImmutableList.builder();
        private String name = "app";
        private Injector parentInjector;
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private @Nullable PanicHandler panicHandler;
        private Consumer<Runnable> shutdownHookRegistrar = hook -> Runtime.getRuntime().addShutdownHook(new Thread(hook));
        private Checkpoints checkpoints = Checkpoints.NONE;

        public Builder setName(String name) {
            this.name = checkNotNull(name);
            return this;
        }

        public Builder addModule(Supplier<? extends Module> moduleSupplier) {
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

        /// Sets what a panic does once the application has discarded its executors' queued tasks. Required for an application driven by
        /// [Application#start()], whose handler arranges, on another thread, for it to be stopped and started again; one driven by [Application#run()] shuts
        /// down after its handler, if any, has run.
        public Builder withPanicHandler(PanicHandler panicHandler) {
            this.panicHandler = checkNotNull(panicHandler);
            return this;
        }

        /// Replaces the JVM shutdown hook registration of [Application#run()], so that a test can fire the hook itself.
        @VisibleForTesting
        Builder withShutdownHookRegistrar(Consumer<Runnable> shutdownHookRegistrar) {
            this.shutdownHookRegistrar = checkNotNull(shutdownHookRegistrar);
            return this;
        }

        /// Lets a test hold [Application#run()] at a [Checkpoints] point to reproduce an interleaving.
        @VisibleForTesting
        Builder withCheckpoints(Checkpoints checkpoints) {
            this.checkpoints = checkNotNull(checkpoints);
            return this;
        }

        @Override
        public Application build() {
            List<Supplier<? extends Module>> moduleSuppliers = moduleSupplierListBuilder.build();
            Module module = new AbstractModule() {
                @Override
                protected void configure() {
                    moduleSuppliers.stream()
                                   .map(Supplier::get)
                                   .forEach(this::install);
                }
            };
            return new Application(name, parentInjector, specifiedAnnotation, () -> module, panicHandler, shutdownHookRegistrar, checkpoints);
        }
    }
}
