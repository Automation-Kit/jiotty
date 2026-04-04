package net.yudichev.jiotty.process;

import net.yudichev.jiotty.common.app.Application;
import org.apache.logging.log4j.LogManager;

import java.util.function.Supplier;

import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

@SuppressWarnings("UseOfSystemOutOrSystemErr")
public final class AppStarter {
    public static void start(Supplier<InitModule> initModuleSupplier) {
        try {
            Application.builder()
                       .setName("init")
                       .addModule(initModuleSupplier)
                       .withAnnotation(forAnnotation(uniqueAnnotation()))
                       .build()
                       .run();
        } finally {
            System.out.println("Shutting down Log4j...");
            LogManager.shutdown();
            System.out.println("... log4j shut down");
        }
    }
}
