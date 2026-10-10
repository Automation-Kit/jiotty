package net.yudichev.jiotty.process;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.process.Bindings.ProcessApplication;
import org.apache.logging.log4j.LogManager;

import java.nio.file.Path;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "JavaPrintToLogpoint"}) // the process reports Log4j's own shutdown on stdout, outliving every logger
public final class AppStarter {
    /// The exit status of a process that stopped on a panic, so that a supervisor restarting only failed processes restarts it.
    @VisibleForTesting
    static final int PANIC_EXIT_STATUS = 70;

    /// Runs the process until it stops. A panic stops it with a non-zero exit status, for its supervisor to restart it.
    public static void start(Supplier<InitModule> initModuleSupplier) {
        start(initModuleSupplier, PanicRecord.NONE);
    }

    /// As [#start(Supplier)], keeping the reason for a panic in `panicRecordFile` so that the restarted process can read it back from the [PanicRecord] bound
    /// in its root injector.
    public static void start(Supplier<InitModule> initModuleSupplier, Path panicRecordFile) {
        start(initModuleSupplier, new FilePanicRecord(panicRecordFile));
    }

    private static void start(Supplier<InitModule> initModuleSupplier, PanicRecord panicRecord) {
        start(initModuleSupplier, panicRecord, AppStarter::shutDownLogging, System::exit);
    }

    /// @param loggingShutdown runs once the application has stopped, whatever stopped it
    /// @param exitAction      given the process's exit status once the application has stopped on a panic
    @VisibleForTesting
    static void start(Supplier<? extends Module> initModuleSupplier, PanicRecord panicRecord, Runnable loggingShutdown, IntConsumer exitAction) {
        // Set on the panicking thread before the panic requests the shutdown, and read here once after run() returns, which that request orders after it. A
        // panic whose handler runs after another request has already ended the run goes unrecorded, the shutdown having been asked for regardless.
        var panicReason = new MutableReference<String>();
        String recordedReason;
        try {
            Application.builder()
                       .setName("init")
                       .addModule(initModuleSupplier)
                       .addModule(() -> new AbstractModule() {
                           @Override
                           protected void configure() {
                               bind(PanicRecord.class).toInstance(panicRecord);
                           }
                       })
                       .withAnnotation(forAnnotation(ProcessApplication.class))
                       .withPanicHandler((reason, _) -> panicReason.set(reason))
                       .build()
                       .run();
            recordedReason = panicReason.get();
            if (recordedReason != null) {
                panicRecord.write(recordedReason);
            }
        } finally {
            loggingShutdown.run();
        }
        if (recordedReason != null) {
            exitAction.accept(PANIC_EXIT_STATUS);
        }
    }

    private static void shutDownLogging() {
        System.out.println("Shutting down Log4j...");
        LogManager.shutdown();
        System.out.println("... log4j shut down");
    }
}
