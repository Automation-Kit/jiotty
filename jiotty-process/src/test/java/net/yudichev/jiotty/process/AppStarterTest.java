package net.yudichev.jiotty.process;

import com.google.inject.AbstractModule;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.process.Bindings.ProcessApplication;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;

class AppStarterTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final CompletableFuture<ApplicationLifecycleControl> processControl = new CompletableFuture<>();
    private final CompletableFuture<PanicRecord> boundPanicRecord = new CompletableFuture<>();
    private final CompletableFuture<Void> loggingShutdownSignal = new CompletableFuture<>();
    private final CompletableFuture<Integer> exitStatus = new CompletableFuture<>();
    /// Whether logging had been shut down by the time the exit status was given.
    private final CompletableFuture<Boolean> loggingShutDownBeforeExit = new CompletableFuture<>();
    @TempDir
    private Path tempDir;
    private FilePanicRecord panicRecord;
    private @Nullable Thread processThread;

    @BeforeEach
    void setUp() {
        panicRecord = new FilePanicRecord(tempDir.resolve("panic-reason"));
    }

    @AfterEach
    void tearDown() {
        if (processThread != null && processThread.isAlive()) {
            processControl.getNow(ApplicationLifecycleControl.NOOP).initiateShutdown();
            awaitProcessEnd();
        }
    }

    @Test
    void aPanicIsRecordedAndStopsTheProcessWithThePanicStatus() {
        startProcess();
        ApplicationLifecycleControl lifecycleControl = awaitProcessStart();

        lifecycleControl.panic("executor UserManagement has a full queue", new RuntimeException("full"));

        awaitProcessEnd();
        assertThat(exitStatus).succeedsWithin(TIMEOUT).isEqualTo(AppStarter.PANIC_EXIT_STATUS);
        assertThat(loggingShutDownBeforeExit).succeedsWithin(TIMEOUT).isEqualTo(true);
        assertThat(panicRecord.read()).contains("executor UserManagement has a full queue");
    }

    @Test
    void aShutdownStopsTheProcessWithoutAnExitStatusOrARecord() {
        startProcess();

        awaitProcessStart().initiateShutdown();

        awaitProcessEnd();
        assertThat(loggingShutdownSignal).isCompleted();
        assertThat(exitStatus).isNotDone();
        assertThat(panicRecord.read()).isEmpty();
    }

    @Test
    void theInitModuleIsGivenThePanicRecord() {
        startProcess();
        awaitProcessStart();

        assertThat(boundPanicRecord).succeedsWithin(TIMEOUT).isSameAs(panicRecord);
    }

    /// Runs the process on its own thread over a module that hands this test the process's lifecycle control and the bound [PanicRecord].
    private void startProcess() {
        var thread = new Thread(() -> AppStarter.start(() -> new AbstractModule() {
            @Override
            protected void configure() {
                requestInjection(new Object() {
                    @Inject
                    void capture(@ProcessApplication ApplicationLifecycleControl lifecycleControl, PanicRecord panicRecord) {
                        processControl.complete(lifecycleControl);
                        boundPanicRecord.complete(panicRecord);
                    }
                });
            }
        }, panicRecord, () -> loggingShutdownSignal.complete(null), status -> {
            loggingShutDownBeforeExit.complete(loggingShutdownSignal.isDone());
            exitStatus.complete(status);
        }), "process");
        processThread = thread;
        thread.start();
    }

    private ApplicationLifecycleControl awaitProcessStart() {
        assertThat(processControl).succeedsWithin(TIMEOUT);
        return processControl.getNow(null);
    }

    private void awaitProcessEnd() {
        Thread thread = processThread;
        assert thread != null : "the process has not been started";
        asUnchecked(() -> thread.join(TIMEOUT));
        assertThat(thread.isAlive()).as("the process stopped").isFalse();
    }
}
