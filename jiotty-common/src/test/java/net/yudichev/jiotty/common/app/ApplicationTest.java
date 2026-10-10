package net.yudichev.jiotty.common.app;

import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.google.inject.BindingAnnotation;
import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import net.yudichev.jiotty.common.async.QueueFullException;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ApplicationTest {
    private static final Logger logger = LogManager.getLogger(ApplicationTest.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /// Written by the components on whichever thread starts or stops them, and read by the test thread once that thread has finished.
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String> panicReasons = new CopyOnWriteArrayList<>();
    private final List<CountDownLatch> latchesToRelease = new CopyOnWriteArrayList<>();
    private final List<SingleThreadedSchedulingExecutor> strayExecutors = new CopyOnWriteArrayList<>();
    private final List<Application> applications = new ArrayList<>();
    private @Nullable RunDrivenApp runDrivenApp;

    /// Releases every blocked component and task first, so each executor's close drains at once.
    @AfterEach
    void tearDown() {
        latchesToRelease.forEach(CountDownLatch::countDown);
        if (runDrivenApp != null) {
            runDrivenApp.shutDownIfRunning();
        }
        Lists.reverse(applications).forEach(Application::stop);
        Closeable.closeSafelyIfNotNull(logger, strayExecutors);
    }

    /// A builder over `modules`, in order, whose panic handler records the reason in [#panicReasons].
    private Application.Builder applicationWith(Module... modules) {
        Application.Builder builder = Application.builder().setName("test").withPanicHandler((reason, _) -> panicReasons.add(reason));
        for (Module module : modules) {
            builder.addModule(() -> module);
        }
        return builder;
    }

    /// Builds the application and registers it for the teardown's stop.
    private Application build(Application.Builder builder) {
        Application application = builder.build();
        applications.add(application);
        return application;
    }

    private static void start(Application application) {
        asUnchecked(application::start);
    }

    private static <T> T instance(Application application, Class<T> type) {
        Injector injector = application.getInjector();
        assertThat(injector).isNotNull();
        return injector.getInstance(type);
    }

    private RecordingComponent component(String name) {
        return new RecordingComponent(name);
    }

    private static Module componentsModule(LifecycleComponent... components) {
        return new AbstractModule() {
            @Override
            protected void configure() {
                for (LifecycleComponent component : components) {
                    bind(LifecycleComponent.class).annotatedWith(uniqueAnnotation()).toInstance(component);
                }
            }
        };
    }

    /// Runs the application on its own thread; the result is also [#runDrivenApp], for the teardown.
    private RunDrivenApp run(Application.Builder builder) {
        var app = new RunDrivenApp(builder);
        runDrivenApp = app;
        app.runThread.start();
        return app;
    }

    /// The control of the start in progress, for a component action running on the run thread.
    private ApplicationLifecycleControl currentControl() {
        assert runDrivenApp != null : "no application is being run";
        return runDrivenApp.currentControl;
    }

    /// An action that completes `blockEntry` and then blocks until the test's teardown or until interrupted.
    private Runnable blockUntilReleased(CompletableFuture<Void> blockEntry) {
        CountDownLatch release = latch();
        return () -> {
            blockEntry.complete(null);
            await(release);
        };
    }

    private CountDownLatch latch() {
        var latch = new CountDownLatch(1);
        latchesToRelease.add(latch);
        return latch;
    }

    /// Occupies `executor`'s thread, then fills its two queue slots and submits one task more, which the full queue rejects.
    ///
    /// @return whether the rejection reports the executor's owner as panicked over it
    private boolean fillQueue(SchedulingExecutor executor) {
        occupy(executor);
        executor.execute("queued", () -> {});
        executor.execute("queued", () -> {});

        return catchThrowableOfType(QueueFullException.class, () -> executor.execute("rejected", () -> {})).ownerPanicked();
    }

    private CountDownLatch occupy(SchedulingExecutor executor) {
        var occupancy = new CountDownLatch(1);
        CountDownLatch release = latch();
        executor.execute("occupying", () -> {
            occupancy.countDown();
            await(release);
        });
        await(occupancy);
        return release;
    }

    private static void await(CountDownLatch latch) {
        assertThat(getAsUnchecked(() -> latch.await(TIMEOUT.toMillis(), MILLISECONDS))).isTrue();
    }

    /// An executor no component closes, closed by the teardown.
    private SingleThreadedSchedulingExecutor createStrayExecutor(String name) {
        SingleThreadedSchedulingExecutor executor = createExecutor(name);
        strayExecutors.add(executor);
        return executor;
    }

    private static SingleThreadedSchedulingExecutor createExecutor(String name) {
        return new SingleThreadedSchedulingExecutor(name, name, 2);
    }

    @Nested
    class Lifecycle {
        @Test
        void componentsStartInBindingOrderAcrossModulesAndStopInReverse() {
            Application application = build(applicationWith(componentsModule(component("A"), component("B")), componentsModule(component("C"))));

            start(application);
            assertThat(events).containsExactly("A start", "B start", "C start");

            application.stop();
            assertThat(events).containsExactly("A start", "B start", "C start", "C stop", "B stop", "A stop");
        }

        @Test
        void startsAndStopsRepeatedly() {
            Application application = build(applicationWith(componentsModule(component("A"))));

            start(application);
            application.stop();
            start(application);
            application.stop();

            assertThat(events).containsExactly("A start", "A stop", "A start", "A stop");
        }

        @Test
        void stopStopsEveryComponentEvenWhenOneThrows() {
            RecordingComponent failingStop = component("B").whenStopping(() -> {
                throw new IllegalStateException("B cannot stop");
            });
            Application application = build(applicationWith(componentsModule(component("A"), failingStop, component("C"))));
            start(application);

            assertThatCode(application::stop).doesNotThrowAnyException();

            assertThat(events).containsExactly("A start", "B start", "C start", "C stop", "B stop", "A stop");
        }

        @Test
        void stopBeforeAnyStartIsANoOp() {
            Application application = build(applicationWith(componentsModule(component("A"))));

            assertThatCode(application::stop).doesNotThrowAnyException();

            assertThat(events).isEmpty();
        }

        /// The component whose start failed is stopped too, so that it releases what it acquired before failing.
        @Test
        void aFailedStartPropagatesTheFailureAndLeavesTheStartedComponentsForStop() {
            RecordingComponent failingStart = component("B").whenStarting(() -> {
                throw new IllegalStateException("B cannot start");
            });
            Application application = build(applicationWith(componentsModule(component("A"), failingStart, component("C"))));

            assertThatThrownBy(application::start).isInstanceOf(IllegalStateException.class).hasMessage("B cannot start");
            assertThat(events).containsExactly("A start", "B start");

            application.stop();
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
        }

        @Test
        void anInterruptedStartThrowsBeforeTheNextComponentAndClearsTheInterrupt() {
            RecordingComponent interruptingStart = component("A").whenStarting(() -> Thread.currentThread().interrupt());
            Application application = build(applicationWith(componentsModule(interruptingStart, component("B"))));

            assertThatThrownBy(application::start).isInstanceOf(InterruptedException.class).hasMessageContaining("1 out of 2");

            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(events).containsExactly("A start");
            application.stop();
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void aStartedApplicationCannotBeStartedAgainUntilStopped() {
            Application application = build(applicationWith(componentsModule(component("A"))));
            start(application);

            assertThatThrownBy(application::start).isInstanceOf(IllegalStateException.class);

            assertThat(events).containsExactly("A start");
        }

        @Test
        void aFailedStartCannotBeStartedAgainUntilStopped() {
            RecordingComponent component = component("A");
            component.whenStarting(() -> {
                if (component.startCount() == 1) {
                    throw new IllegalStateException("A cannot start the first time");
                }
            });
            Application application = build(applicationWith(componentsModule(component)));
            assertThatThrownBy(application::start).isInstanceOf(IllegalStateException.class).hasMessage("A cannot start the first time");

            assertThatThrownBy(application::start).isInstanceOf(IllegalStateException.class).hasMessageContaining("already started");

            application.stop();
            start(application);
            assertThat(events).containsExactly("A start", "A stop", "A start");
        }

        /// A stop from a second thread waits for the start in progress.
        @Test
        void aConcurrentStopWaitsForTheStartToFinish() {
            var blockEntry = new CompletableFuture<Void>();
            RecordingComponent blockingStart = component("A").whenStarting(blockUntilReleased(blockEntry));
            Application application = build(applicationWith(componentsModule(blockingStart, component("B"))));
            CompletableFuture<Void> startCall = CompletableFuture.runAsync(() -> start(application));
            assertThat(blockEntry).succeedsWithin(TIMEOUT);

            CompletableFuture<Void> stopCall = CompletableFuture.runAsync(application::stop);
            latchesToRelease.forEach(CountDownLatch::countDown);

            assertThat(startCall).succeedsWithin(TIMEOUT);
            assertThat(stopCall).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
        }

        @Test
        void closeStopsTheApplicationOnce() {
            Application application = build(applicationWith(componentsModule(component("A"))));
            start(application);

            application.close();
            application.close();

            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void formatsAsItsName() {
            assertThat(build(applicationWith())).hasToString("Application test");
        }
    }

    @Nested
    class InjectorAccess {
        @Test
        void theInjectorIsNullBeforeTheFirstStart() {
            assertThat(build(applicationWith()).getInjector()).isNull();
        }

        @Test
        void eachStartHasItsOwnInjector() {
            Application application = build(applicationWith());
            start(application);
            Injector firstInjector = application.getInjector();
            assertThat(firstInjector).isNotNull();
            application.stop();

            start(application);

            assertThat(application.getInjector()).isNotNull().isNotSameAs(firstInjector);
        }

        @Test
        void theInjectorIsNullAfterAFailedStart() {
            Application application = build(applicationWith(componentsModule(component("A").whenStarting(() -> {
                throw new IllegalStateException("A cannot start");
            }))));

            assertThatThrownBy(application::start).isInstanceOf(IllegalStateException.class);

            assertThat(application.getInjector()).isNull();
        }

        @Test
        void theInjectorIsNullAfterAnInterruptedStart() {
            Application application = build(applicationWith(componentsModule(component("A").whenStarting(() -> Thread.currentThread().interrupt()),
                                                                             component("B"))));

            assertThatThrownBy(application::start).isInstanceOf(InterruptedException.class);

            assertThat(application.getInjector()).isNull();
        }

        @Test
        void theInjectorIsNullAfterStop() {
            Application application = build(applicationWith());
            start(application);

            application.stop();

            assertThat(application.getInjector()).isNull();
        }

        /// Read from another thread while a component is still starting, the injector is null at once.
        @Test
        void theInjectorIsReadWithoutWaitingForAStartInProgress() {
            var blockEntry = new CompletableFuture<Void>();
            Application application = build(applicationWith(componentsModule(component("A").whenStarting(blockUntilReleased(blockEntry)))));
            CompletableFuture<Void> startCall = CompletableFuture.runAsync(() -> start(application));
            assertThat(blockEntry).succeedsWithin(TIMEOUT);

            assertThat(CompletableFuture.supplyAsync(application::getInjector)).succeedsWithin(TIMEOUT).isNull();

            latchesToRelease.forEach(CountDownLatch::countDown);
            assertThat(startCall).succeedsWithin(TIMEOUT);
            assertThat(application.getInjector()).isNotNull();
        }

        /// Read from another thread while a component is still stopping, the injector is null at once.
        @Test
        void theInjectorIsReadWithoutWaitingForAStopInProgress() {
            var blockEntry = new CompletableFuture<Void>();
            Application application = build(applicationWith(componentsModule(component("A").whenStopping(blockUntilReleased(blockEntry)))));
            start(application);
            CompletableFuture<Void> stopCall = CompletableFuture.runAsync(application::stop);
            assertThat(blockEntry).succeedsWithin(TIMEOUT);

            assertThat(CompletableFuture.supplyAsync(application::getInjector)).succeedsWithin(TIMEOUT).isNull();

            latchesToRelease.forEach(CountDownLatch::countDown);
            assertThat(stopCall).succeedsWithin(TIMEOUT);
        }

        @Test
        void aChildApplicationResolvesItsParentsBindings() {
            Application parent = build(applicationWith(new AbstractModule() {
                @Override
                protected void configure() {
                    bind(String.class).toInstance("from parent");
                }
            }));
            start(parent);
            Application child = build(Application.builder()
                                                 .setName("child")
                                                 .withParentInjector(checkNotNull(parent.getInjector()))
                                                 .withAnnotation(forAnnotation(Scoped.class))
                                                 .addModule(() -> componentsModule(component("A")))
                                                 .withPanicHandler((reason, _) -> panicReasons.add(reason)));

            start(child);

            assertThat(instance(child, String.class)).isEqualTo("from parent");
            assertThat(events).containsExactly("A start");
        }
    }

    @Nested
    class LifecycleControl {
        @Test
        void eachStartBindsItsOwnLifecycleControl() {
            Application application = build(applicationWith());
            start(application);
            ApplicationLifecycleControl firstStartControl = instance(application, ApplicationLifecycleControl.class);
            application.stop();

            start(application);

            assertThat(instance(application, ApplicationLifecycleControl.class)).isNotSameAs(firstStartControl);
        }

        @Test
        void theLifecycleControlIsBoundUnderTheApplicationsAnnotation() {
            Application application = build(applicationWith().withAnnotation(forAnnotation(Scoped.class)));
            start(application);
            Injector injector = application.getInjector();
            assertThat(injector).isNotNull();

            assertThat(injector.getInstance(Key.get(ApplicationLifecycleControl.class, Scoped.class))).isNotNull();
            assertThatThrownBy(() -> injector.getInstance(ApplicationLifecycleControl.class)).isInstanceOf(ConfigurationException.class);
        }

        /// The path a sub-application escalates its own panic along.
        @Test
        void theLifecycleControlPanicsTheApplication() {
            Application application = build(applicationWith());
            start(application);

            instance(application, ApplicationLifecycleControl.class).panic("escalated", new RuntimeException("escalated"));

            assertThat(panicReasons).containsExactly("escalated");
        }

        @Test
        void aPanicIsHandledOncePerStart() {
            Application application = build(applicationWith(new ExecutorsModule()));
            start(application);

            fillQueue(instance(application, StartedExecutor.class).executor);
            fillQueue(instance(application, ConstructedExecutor.class).executor);
            assertThat(panicReasons).hasSize(1);

            application.stop();
            start(application);
            fillQueue(instance(application, StartedExecutor.class).executor);
            assertThat(panicReasons).as("the next start panics afresh").hasSize(2);
        }

        @Test
        void aPanicAfterStopIsIgnored() {
            Application application = build(applicationWith());
            start(application);
            ApplicationLifecycleControl lifecycleControl = instance(application, ApplicationLifecycleControl.class);
            application.stop();

            lifecycleControl.panic("late", new RuntimeException("late"));

            assertThat(panicReasons).isEmpty();
        }

        @Test
        void aPanicDuringAComponentsStopIsIgnored() {
            var lifecycleControl = new MutableReference<ApplicationLifecycleControl>();
            RecordingComponent panickingStop = component("A")
                    .whenStopping(() -> lifecycleControl.get().panic("while stopping", new RuntimeException("while stopping")));
            Application application = build(applicationWith(componentsModule(panickingStop)));
            start(application);
            lifecycleControl.set(instance(application, ApplicationLifecycleControl.class));

            application.stop();

            assertThat(panicReasons).isEmpty();
        }

        /// The control a start binds stays with that start: once it has stopped, its panic reaches neither the handler nor a later start.
        @Test
        void aPanicOfAStoppedStartLeavesTheNextStartRunning() {
            Application application = build(applicationWith(new ExecutorsModule()));
            start(application);
            ApplicationLifecycleControl firstStartControl = instance(application, ApplicationLifecycleControl.class);
            application.stop();
            start(application);

            firstStartControl.panic("late", new RuntimeException("late"));
            fillQueue(instance(application, StartedExecutor.class).executor);

            assertThat(panicReasons).containsExactly("executor started has a full queue");
        }

        @Test
        void anApplicationStartedWithoutAPanicHandlerCannotPanic() {
            Application unhandledApplication = build(Application.builder().setName("unhandled"));
            start(unhandledApplication);
            ApplicationLifecycleControl lifecycleControl = instance(unhandledApplication, ApplicationLifecycleControl.class);

            assertThatThrownBy(() -> lifecycleControl.panic("unhandled", new RuntimeException("unhandled"))).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aLatePanicOfAStoppedStartWithoutAPanicHandlerIsIgnored() {
            Application unhandledApplication = build(Application.builder().setName("unhandled"));
            start(unhandledApplication);
            ApplicationLifecycleControl lifecycleControl = instance(unhandledApplication, ApplicationLifecycleControl.class);
            unhandledApplication.stop();

            assertThatCode(() -> lifecycleControl.panic("late", new RuntimeException("late"))).doesNotThrowAnyException();
        }

        /// A panic and a stop both go through while the handler is still running.
        @Test
        void thePanicHandlerRunsOutsideAnyLock() {
            var handlerEntered = new CompletableFuture<Void>();
            CountDownLatch releaseHandler = latch();
            Application application = build(Application.builder().setName("test").withPanicHandler((reason, _) -> {
                panicReasons.add(reason);
                handlerEntered.complete(null);
                await(releaseHandler);
            }));
            start(application);
            ApplicationLifecycleControl lifecycleControl = instance(application, ApplicationLifecycleControl.class);

            CompletableFuture<Void> firstPanic = CompletableFuture.runAsync(() -> lifecycleControl.panic("first", new RuntimeException("first")));
            assertThat(handlerEntered).succeedsWithin(TIMEOUT);
            assertThat(CompletableFuture.runAsync(() -> lifecycleControl.panic("second", new RuntimeException("second"))))
                    .as("a panic while the handler runs returns at once")
                    .succeedsWithin(TIMEOUT);
            application.stop();

            releaseHandler.countDown();
            assertThat(firstPanic).succeedsWithin(TIMEOUT);
            assertThat(panicReasons).containsExactly("first");
        }

        /// A task the handler queues runs, while every task queued before the panic is skipped.
        @Test
        void aPanicDiscardsTheTasksQueuedOnEveryExecutorOfTheApplicationBeforeItsHandlerRuns() {
            var otherExecutor = new MutableReference<SchedulingExecutor>();
            var queuedByHandlerRan = new CompletableFuture<Void>();
            Application application = build(Application.builder()
                                                       .setName("test")
                                                       .addModule(ExecutorsModule::new)
                                                       .withPanicHandler((reason, _) -> {
                                                           panicReasons.add(reason);
                                                           otherExecutor.get().execute("queued by handler", () -> queuedByHandlerRan.complete(null));
                                                       }));
            start(application);
            SchedulingExecutor constructedExecutor = instance(application, ConstructedExecutor.class).executor;
            otherExecutor.set(constructedExecutor);
            CountDownLatch otherRelease = occupy(constructedExecutor);
            var discardedTaskRan = new AtomicBoolean();
            constructedExecutor.execute("discarded", () -> discardedTaskRan.set(true));

            fillQueue(instance(application, StartedExecutor.class).executor);
            otherRelease.countDown();

            assertThat(queuedByHandlerRan).succeedsWithin(TIMEOUT);
            assertThat(discardedTaskRan).isFalse();
            assertThat(panicReasons).containsExactly("executor started has a full queue");
        }

        /// The start has panicked whatever its handler then does.
        @Test
        void aFullQueueWhoseHandlerFailsStillReportsTheOwnerPanicked() {
            Application application = build(Application.builder().setName("test").addModule(ExecutorsModule::new).withPanicHandler((reason, _) -> {
                panicReasons.add(reason);
                throw new IllegalStateException("handler failed");
            }));
            start(application);

            assertThat(fillQueue(instance(application, StartedExecutor.class).executor)).as("owner panicked").isTrue();

            assertThat(panicReasons).containsExactly("executor started has a full queue");
        }

        @Test
        void aSubApplicationEscalatesItsPanicToItsParent() {
            Application parent = build(applicationWith());
            start(parent);
            ApplicationLifecycleControl parentControl = instance(parent, ApplicationLifecycleControl.class);
            Application child = build(Application.builder()
                                                 .setName("child")
                                                 .withParentInjector(checkNotNull(parent.getInjector()))
                                                 .withAnnotation(forAnnotation(Scoped.class))
                                                 .addModule(ExecutorsModule::new)
                                                 .withPanicHandler(parentControl::panic));
            start(child);

            fillQueue(instance(child, StartedExecutor.class).executor);

            assertThat(panicReasons).containsExactly("executor started has a full queue");
        }

        @Test
        void shutdownAndRestartRequestsNeedARunDrivenApplication() {
            Application application = build(applicationWith());
            start(application);
            ApplicationLifecycleControl lifecycleControl = instance(application, ApplicationLifecycleControl.class);

            assertThat(lifecycleControl.restarting()).isFalse();
            assertThatThrownBy(lifecycleControl::initiateShutdown).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(lifecycleControl::initiateRestart).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class ExecutorOwnership {
        @Test
        void aFullQueueOnAnExecutorCreatedWhileStartingPanicsTheApplication() {
            Application application = build(applicationWith(new ExecutorsModule()));
            start(application);

            assertThat(fillQueue(instance(application, StartedExecutor.class).executor)).as("owner panicked").isTrue();

            assertThat(panicReasons).containsExactly("executor started has a full queue");
        }

        @Test
        void anExecutorCreatedInAConstructorBelongsToTheApplicationToo() {
            Application application = build(applicationWith(new ExecutorsModule()));
            start(application);

            fillQueue(instance(application, ConstructedExecutor.class).executor);

            assertThat(panicReasons).containsExactly("executor constructed has a full queue");
        }

        @Test
        void anExecutorCreatedOutsideAnyStartHasNoOwner() {
            start(build(applicationWith()));
            SingleThreadedSchedulingExecutor unownedExecutor = createStrayExecutor("unowned");

            assertThat(fillQueue(unownedExecutor)).as("owner panicked").isFalse();

            assertThat(panicReasons).isEmpty();
        }

        /// Ownership is bound to the starting thread: an executor a component creates on another thread, such as inside a task, has none.
        @Test
        void anExecutorCreatedOnAnotherThreadDuringTheStartHasNoOwner() {
            var foreignExecutor = new MutableReference<SingleThreadedSchedulingExecutor>();
            RecordingComponent creatingElsewhere = component("A").whenStarting(() -> {
                CompletableFuture<SingleThreadedSchedulingExecutor> creation = CompletableFuture.supplyAsync(() -> createStrayExecutor("foreign"));
                foreignExecutor.set(getAsUnchecked(() -> creation.get(TIMEOUT.toMillis(), MILLISECONDS)));
            });
            start(build(applicationWith(componentsModule(creatingElsewhere))));

            assertThat(fillQueue(foreignExecutor.get())).as("owner panicked").isFalse();

            assertThat(panicReasons).isEmpty();
        }

        @Test
        void anExecutorOfAStoppedStartPanicsNothing() {
            var keptExecutor = new MutableReference<SingleThreadedSchedulingExecutor>();
            RecordingComponent keepingItsExecutor = component("A").whenStarting(() -> {
                if (keptExecutor.get() == null) {
                    keptExecutor.set(createStrayExecutor("kept"));
                }
            });
            Application application = build(applicationWith(componentsModule(keepingItsExecutor)));
            start(application);
            application.stop();
            start(application);

            assertThat(fillQueue(keptExecutor.get())).as("owner panicked").isFalse();

            assertThat(panicReasons).isEmpty();
        }
    }

    @Nested
    class Run {
        @Test
        void runStartsTheComponentsAndStopsThemOnShutdown() {
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"), component("B"))));
            ApplicationLifecycleControl lifecycleControl = app.awaitStarted();
            assertThat(events).containsExactly("A start", "B start");

            lifecycleControl.initiateShutdown();

            app.awaitEnd();
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
        }

        @Test
        void runCanOnlyBeCalledOnce() {
            RunDrivenApp app = run(applicationWith());
            app.awaitStarted().initiateShutdown();
            app.awaitEnd();

            assertThatThrownBy(app.application::run).isInstanceOf(IllegalStateException.class);
        }

        /// The first stop sees the restart as in progress and its own repeated restart request ignored; the start that follows sees no restart.
        @Test
        void aRestartStopsTheComponentsAndStartsThemAgain() {
            var restartingWhileStopping = new CompletableFuture<Boolean>();
            RecordingComponent component = component("A");
            component.whenStopping(() -> {
                if (component.stopCount() == 1) {
                    restartingWhileStopping.complete(currentControl().restarting());
                    currentControl().initiateRestart();
                }
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component)));
            ApplicationLifecycleControl firstStartControl = app.awaitStarted();
            assertThat(firstStartControl.restarting()).isFalse();

            firstStartControl.initiateRestart();

            ApplicationLifecycleControl secondStartControl = app.awaitStarted();
            assertThat(restartingWhileStopping).succeedsWithin(TIMEOUT).isEqualTo(true);
            assertThat(secondStartControl).isNotSameAs(firstStartControl);
            assertThat(secondStartControl.restarting()).isFalse();
            secondStartControl.initiateShutdown();
            app.awaitEnd();
            assertThat(events).containsExactly("A start", "A stop", "A start", "A stop");
        }

        @Test
        void aFailedStartEndsTheRun() {
            RecordingComponent failingStart = component("B").whenStarting(() -> {
                throw new IllegalStateException("B cannot start");
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"), failingStart, component("C"))));

            app.awaitEnd();

            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
            assertThat(panicReasons).isEmpty();
        }

        @Test
        void aShutdownWhileStartingInterruptsTheStart() {
            var blockEntry = new CompletableFuture<Void>();
            RecordingComponent blockingStart = component("B").whenStarting(blockUntilReleased(blockEntry));
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"), blockingStart, component("C"))));
            ApplicationLifecycleControl lifecycleControl = app.awaitStarting();
            assertThat(blockEntry).succeedsWithin(TIMEOUT);

            lifecycleControl.initiateShutdown();

            app.awaitEnd();
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
        }

        /// The process's own application: its handler records the panic, then the application shuts down so that its supervisor restarts the process.
        @Test
        void aRunDrivenApplicationShutsDownAfterItsPanicHandler() {
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))));

            app.awaitStarted().panic("full", new RuntimeException("full"));

            app.awaitEnd();
            assertThat(panicReasons).containsExactly("full");
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void aRunDrivenApplicationShutsDownEvenWhenItsPanicHandlerThrows() {
            RunDrivenApp app = run(Application.builder().setName("run").withPanicHandler((_, _) -> {
                throw new IllegalStateException("handler failed");
            }));
            ApplicationLifecycleControl lifecycleControl = app.awaitStarted();

            assertThatThrownBy(() -> lifecycleControl.panic("full", new RuntimeException("full")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("handler failed");

            app.awaitEnd();
        }

        @Test
        void aRunDrivenApplicationWithoutAPanicHandlerShutsDown() {
            RunDrivenApp app = run(Application.builder().setName("run unhandled"));

            app.awaitStarted().panic("full", new RuntimeException("full"));

            app.awaitEnd();
        }

        /// The panic's stop interrupts the start, and the run ends.
        @Test
        void aPanicWhileStartingStopsARunDrivenApplication() {
            RecordingComponent panickingStart = component("A").whenStarting(() -> currentControl().panic("starting", new RuntimeException("starting")));
            RunDrivenApp app = run(applicationWith(componentsModule(panickingStart, component("B"))));

            app.awaitEnd();

            assertThat(panicReasons).containsExactly("starting");
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void theShutdownHookStopsTheApplicationAndReturnsOnceItHasStopped() {
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))));
            app.awaitStarted();

            CompletableFuture<List<String>> eventsWhenHookReturned = app.fireShutdownHook().thenApply(_ -> List.copyOf(events));

            app.awaitEnd();
            assertThat(eventsWhenHookReturned).succeedsWithin(TIMEOUT).isEqualTo(List.of("A start", "A stop"));
        }

        @Test
        void theShutdownHookAfterTheRunHasEndedDoesNothing() {
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))));
            app.awaitStarted().initiateShutdown();
            app.awaitEnd();

            assertThat(app.fireShutdownHook()).succeedsWithin(TIMEOUT);

            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void theShutdownHookWhileStartingInterruptsTheStart() {
            var blockEntry = new CompletableFuture<Void>();
            RecordingComponent blockingStart = component("B").whenStarting(blockUntilReleased(blockEntry));
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"), blockingStart, component("C"))));
            app.awaitStarting();
            assertThat(blockEntry).succeedsWithin(TIMEOUT);

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();

            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop");
        }

        @Test
        void theShutdownHookDuringARestartsStartEndsTheRun() {
            var secondStartBlockEntry = new CompletableFuture<Void>();
            RecordingComponent component = component("B");
            Runnable blockingAction = blockUntilReleased(secondStartBlockEntry);
            component.whenStarting(() -> {
                if (component.startCount() == 2) {
                    blockingAction.run();
                }
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"), component)));
            app.awaitStarted().initiateRestart();
            assertThat(secondStartBlockEntry).succeedsWithin(TIMEOUT);

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();

            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop", "A start", "B start", "B stop", "A stop");
        }

        /// The hook finds the components stopped and the loop yet to decide on the restart.
        @Test
        void theShutdownHookBetweenARestartsStopAndItsNextStartEndsTheRun() {
            var checkpoints = new HoldingCheckpoints();
            HeldCheckpoint firstStopCheckpoint = checkpoints.holdAfterStop(1);
            HeldCheckpoint shutdownRequestCheckpoint = checkpoints.holdAfterJvmShutdownRequested();
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))).withCheckpoints(checkpoints));
            app.awaitStarted().initiateRestart();
            firstStopCheckpoint.awaitReached();

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();
            shutdownRequestCheckpoint.awaitReached();
            firstStopCheckpoint.release();
            shutdownRequestCheckpoint.release();

            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void aShutdownRequestedAsARestartIsAboutToStartPreventsTheStart() {
            var checkpoints = new HoldingCheckpoints();
            HeldCheckpoint secondStartCheckpoint = checkpoints.holdBeforeStart(2);
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))).withCheckpoints(checkpoints));
            ApplicationLifecycleControl lifecycleControl = app.awaitStarted();
            lifecycleControl.initiateRestart();
            secondStartCheckpoint.awaitReached();

            lifecycleControl.initiateShutdown();
            secondStartCheckpoint.release();

            app.awaitEnd();
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void theShutdownHookAsTheFirstStartIsAboutToBeginEndsTheRun() {
            var checkpoints = new HoldingCheckpoints();
            HeldCheckpoint firstStartCheckpoint = checkpoints.holdBeforeStart(1);
            HeldCheckpoint shutdownRequestCheckpoint = checkpoints.holdAfterJvmShutdownRequested();
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))).withCheckpoints(checkpoints));
            firstStartCheckpoint.awaitReached();

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();
            shutdownRequestCheckpoint.awaitReached();
            firstStartCheckpoint.release();
            shutdownRequestCheckpoint.release();

            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).isEmpty();
        }

        /// The JVM shutdown hook fires while a restart request is being accepted: the application stops and does not start again.
        @Test
        void theShutdownHookWhileARestartIsBeingAcceptedPreventsTheRestart() {
            var checkpoints = new HoldingCheckpoints();
            HeldCheckpoint restartAcceptanceCheckpoint = checkpoints.holdAfterRestartAccepted();
            HeldCheckpoint firstStopCheckpoint = checkpoints.holdAfterStop(1);
            HeldCheckpoint shutdownRequestCheckpoint = checkpoints.holdAfterJvmShutdownRequested();
            RunDrivenApp app = run(applicationWith(componentsModule(component("A"))).withCheckpoints(checkpoints));
            ApplicationLifecycleControl lifecycleControl = app.awaitStarted();
            CompletableFuture<Void> restartRequest = CompletableFuture.runAsync(lifecycleControl::initiateRestart);
            restartAcceptanceCheckpoint.awaitReached();

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();
            restartAcceptanceCheckpoint.release();
            shutdownRequestCheckpoint.awaitReached();
            shutdownRequestCheckpoint.release();
            firstStopCheckpoint.awaitReached();
            firstStopCheckpoint.release();

            assertThat(restartRequest).succeedsWithin(TIMEOUT);
            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void aRestartRequestedWhileTheJvmIsShuttingDownIsRefused() {
            var restartRefusal = new CompletableFuture<Throwable>();
            RecordingComponent component = component("A");
            component.whenStopping(() -> {
                if (component.stopCount() == 1) {
                    restartRefusal.complete(catchThrowable(() -> currentControl().initiateRestart()));
                }
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component)));
            app.awaitStarted();

            CompletableFuture<Void> hookReturned = app.fireShutdownHook();

            assertThat(restartRefusal).succeedsWithin(TIMEOUT).isInstanceOf(IllegalStateException.class);
            app.awaitEnd();
            assertThat(hookReturned).succeedsWithin(TIMEOUT);
            assertThat(events).containsExactly("A start", "A stop");
        }

        @Test
        void aShutdownRequestedDuringARestartsStopEndsTheRun() {
            RecordingComponent component = component("A");
            component.whenStopping(() -> {
                if (component.stopCount() == 1) {
                    currentControl().initiateShutdown();
                }
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component)));

            app.awaitStarted().initiateRestart();

            app.awaitEnd();
            assertThat(events).containsExactly("A start", "A stop");
        }

        /// A late shutdown request, such as a panic handler's, arriving while the components are being stopped must not interrupt their teardown.
        @Test
        void aShutdownRequestedWhileStoppingAfterAFailedStartDoesNotInterruptTheStop() {
            var interruptedWhileStopping = new CompletableFuture<Boolean>();
            RecordingComponent component = component("A").whenStopping(() -> {
                currentControl().initiateShutdown();
                interruptedWhileStopping.complete(Thread.currentThread().isInterrupted());
            });
            RecordingComponent failingStart = component("B").whenStarting(() -> {
                throw new IllegalStateException("B cannot start");
            });
            RunDrivenApp app = run(applicationWith(componentsModule(component, failingStart)));

            app.awaitEnd();

            assertThat(interruptedWhileStopping).succeedsWithin(TIMEOUT).isEqualTo(false);
        }

        @Test
        void aRestartRequestedWhileStoppingAfterAFailedStartStartsAgain() {
            RecordingComponent restartingComponent = component("A");
            restartingComponent.whenStopping(() -> {
                if (restartingComponent.stopCount() == 1) {
                    currentControl().initiateRestart();
                }
            });
            RecordingComponent failingFirstStart = component("B");
            failingFirstStart.whenStarting(() -> {
                if (failingFirstStart.startCount() == 1) {
                    throw new IllegalStateException("B cannot start the first time");
                }
            });
            RunDrivenApp app = run(applicationWith(componentsModule(restartingComponent, failingFirstStart)));

            ApplicationLifecycleControl secondStartControl = app.awaitStarted();

            secondStartControl.initiateShutdown();
            app.awaitEnd();
            assertThat(events).containsExactly("A start", "B start", "B stop", "A stop", "A start", "B start", "B stop", "A stop");
        }
    }

    /// Records each of its starts and stops in [#events] under its name, then runs the action given for that transition.
    private final class RecordingComponent extends BaseLifecycleComponent {
        private final String name;
        private Runnable startAction = () -> {};
        private Runnable stopAction = () -> {};
        private int startCount;
        private int stopCount;

        private RecordingComponent(String name) {
            this.name = checkNotNull(name);
        }

        @Override
        protected void doStart() {
            startCount++;
            events.add(name + " start");
            startAction.run();
        }

        @Override
        protected void doStop() {
            stopCount++;
            events.add(name + " stop");
            stopAction.run();
        }

        @Override
        public String name() {
            return name;
        }

        public RecordingComponent whenStarting(Runnable action) {
            startAction = checkNotNull(action);
            return this;
        }

        public RecordingComponent whenStopping(Runnable action) {
            stopAction = checkNotNull(action);
            return this;
        }

        /// How many times this component has been started, including the start in progress.
        public int startCount() {
            return startCount;
        }

        /// How many times this component has been stopped, including the stop in progress.
        public int stopCount() {
            return stopCount;
        }
    }

    /// An application driven by [Application#run()] on its own thread, with the JVM shutdown hook captured for the test to fire.
    private static final class RunDrivenApp {
        private final BlockingQueue<ApplicationLifecycleControl> startingControls = new LinkedBlockingQueue<>();
        private final BlockingQueue<ApplicationLifecycleControl> startedControls = new LinkedBlockingQueue<>();
        private final CompletableFuture<Runnable> shutdownHook = new CompletableFuture<>();
        /// Completes as [Application#run()] returns, exceptionally if it threw.
        private final CompletableFuture<Void> runOutcome = new CompletableFuture<>();
        private final Application application;
        private final Thread runThread;
        /// The control of the start in progress: set as its injector is created and read by component actions, both on the run thread.
        private ApplicationLifecycleControl currentControl;
        /// The latest control the test thread has seen, which the teardown shuts down.
        private @Nullable ApplicationLifecycleControl latestKnownControl;

        private RunDrivenApp(Application.Builder builder) {
            application = builder.addModule(() -> new AbstractModule() {
                                     @Override
                                     protected void configure() {
                                         requestInjection(new Object() {
                                             @Inject
                                             void capture(ApplicationLifecycleControl lifecycleControl) {
                                                 currentControl = lifecycleControl;
                                                 startingControls.add(lifecycleControl);
                                             }
                                         });
                                         // Bound last, so its start means every component of the start has started.
                                         bind(LifecycleComponent.class).annotatedWith(uniqueAnnotation()).toInstance(new BaseLifecycleComponent() {
                                             @Override
                                             protected void doStart() {
                                                 startedControls.add(currentControl);
                                             }
                                         });
                                     }
                                 })
                                 .withShutdownHookRegistrar(shutdownHook::complete)
                                 .build();
            runThread = new Thread(() -> {
                try {
                    application.run();
                    runOutcome.complete(null);
                } catch (Throwable e) {
                    runOutcome.completeExceptionally(e);
                }
            }, "application-run");
        }

        /// @return the control of the next start once its injector exists, which is before any component has started
        public ApplicationLifecycleControl awaitStarting() {
            return take(startingControls, "a start to begin");
        }

        /// @return the control of the next start once all its components have started
        public ApplicationLifecycleControl awaitStarted() {
            return take(startedControls, "a start to complete");
        }

        private ApplicationLifecycleControl take(BlockingQueue<ApplicationLifecycleControl> controls, String awaited) {
            ApplicationLifecycleControl control = getAsUnchecked(() -> controls.poll(TIMEOUT.toMillis(), MILLISECONDS));
            assertThat(control).as("waited %s for %s", TIMEOUT, awaited).isNotNull();
            latestKnownControl = control;
            return control;
        }

        /// Runs the captured JVM shutdown hook on its own thread, as the JVM does.
        public CompletableFuture<Void> fireShutdownHook() {
            assertThat(shutdownHook).as("run() registers a shutdown hook").succeedsWithin(TIMEOUT);
            return CompletableFuture.runAsync(shutdownHook.getNow(null));
        }

        public void awaitEnd() {
            assertThat(runOutcome).as("run() returned once the application shut down").succeedsWithin(TIMEOUT);
        }

        public void shutDownIfRunning() {
            if (!runThread.isAlive()) {
                return;
            }
            ApplicationLifecycleControl laterControl;
            while ((laterControl = startingControls.poll()) != null) {
                latestKnownControl = laterControl;
            }
            if (latestKnownControl != null) {
                latestKnownControl.initiateShutdown();
            }
            awaitEnd();
        }
    }

    /// Holds the thread at each checkpoint a test has armed, and lets it through every other one.
    private final class HoldingCheckpoints implements Checkpoints {
        private @Nullable HeldCheckpoint beforeStart;
        private @Nullable HeldCheckpoint afterStop;
        private @Nullable HeldCheckpoint afterRestartAccepted;
        private @Nullable HeldCheckpoint afterJvmShutdownRequested;

        /// @param arrival which pass of the checkpoint to hold, counting from 1
        public HeldCheckpoint holdBeforeStart(int arrival) {
            beforeStart = new HeldCheckpoint(arrival);
            return beforeStart;
        }

        /// @param arrival which pass of the checkpoint to hold, counting from 1
        public HeldCheckpoint holdAfterStop(int arrival) {
            afterStop = new HeldCheckpoint(arrival);
            return afterStop;
        }

        public HeldCheckpoint holdAfterRestartAccepted() {
            afterRestartAccepted = new HeldCheckpoint(1);
            return afterRestartAccepted;
        }

        public HeldCheckpoint holdAfterJvmShutdownRequested() {
            afterJvmShutdownRequested = new HeldCheckpoint(1);
            return afterJvmShutdownRequested;
        }

        @Override
        public boolean beforeStart() {
            return pass(beforeStart);
        }

        @Override
        public boolean afterStop() {
            return pass(afterStop);
        }

        @Override
        public boolean afterRestartAccepted() {
            return pass(afterRestartAccepted);
        }

        @Override
        public boolean afterJvmShutdownRequested() {
            return pass(afterJvmShutdownRequested);
        }

        private static boolean pass(@Nullable HeldCheckpoint checkpoint) {
            return checkpoint == null || checkpoint.pass();
        }
    }

    /// A checkpoint whose `arrivalToHold`-th pass waits for [#release()], which the teardown also performs.
    private final class HeldCheckpoint {
        private final int arrivalToHold;
        private final CompletableFuture<Void> arrival = new CompletableFuture<>();
        private final CountDownLatch release = latch();
        /// Counted by the one thread that passes this checkpoint.
        private int arrivals;

        private HeldCheckpoint(int arrivalToHold) {
            this.arrivalToHold = arrivalToHold;
        }

        public boolean pass() {
            if (++arrivals == arrivalToHold) {
                arrival.complete(null);
                awaitRelease();
            }
            return true;
        }

        /// Waits with a bound, keeping for the held thread an interrupt that lands meanwhile: that interrupt is what some tests hold the thread to receive.
        private void awaitRelease() {
            long deadlineNanoTime = System.nanoTime() + TIMEOUT.toNanos();
            boolean interrupted = false;
            while (true) {
                try {
                    assertThat(release.await(deadlineNanoTime - System.nanoTime(), NANOSECONDS)).as("the checkpoint is released").isTrue();
                    break;
                } catch (InterruptedException _) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        public void awaitReached() {
            assertThat(arrival).as("the checkpoint is reached").succeedsWithin(TIMEOUT);
        }

        public void release() {
            release.countDown();
        }
    }

    private static final class ExecutorsModule extends AbstractModule {
        @Override
        protected void configure() {
            bind(StartedExecutor.class).in(Singleton.class);
            bind(LifecycleComponent.class).annotatedWith(uniqueAnnotation()).to(StartedExecutor.class);
            bind(ConstructedExecutor.class).in(Singleton.class);
            bind(LifecycleComponent.class).annotatedWith(uniqueAnnotation()).to(ConstructedExecutor.class);
        }
    }

    /// Creates its executor as it starts.
    static final class StartedExecutor extends BaseLifecycleComponent {
        private SchedulingExecutor executor;

        @Override
        protected void doStart() {
            executor = createExecutor("started");
        }

        @Override
        protected void doStop() {
            Closeable.closeIfNotNull(executor);
        }
    }

    /// Creates its executor as Guice constructs it, which happens inside the start because singletons are built lazily.
    static final class ConstructedExecutor extends BaseLifecycleComponent {
        private final SchedulingExecutor executor = createExecutor("constructed");

        @Override
        protected void doStop() {
            Closeable.closeIfNotNull(executor);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Scoped {
    }
}
