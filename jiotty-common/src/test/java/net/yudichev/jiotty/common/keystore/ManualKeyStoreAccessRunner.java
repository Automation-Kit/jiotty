package net.yudichev.jiotty.common.keystore;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Paths;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;

final class ManualKeyStoreAccessRunner {

    @SuppressWarnings("StaticVariableMayNotBeInitialized")
    private static String alias;

    static void main(String[] args) {
        alias = args[2];
        Application.builder()
                   .addModule(ExecutorModule::new)
                   .addModule(() -> KeyStoreAccessModule.builder()
                                                        .setPathToKeystore(literally(Paths.get(args[0])))
                                                        .setKeystorePass(literally(args[1]))
                                                        .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(Runner.class);
                           installLifecycleComponentModule(new KeyStoreEntryModule(literally(alias), forAnnotation(Alias.class)));
                       }
                   })
                   .build()
                   .run();

    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Alias {
    }

    @SuppressWarnings("CallToSystemExit")
    private static class Runner extends BaseLifecycleComponent {
        private static final Logger logger = LogManager.getLogger(Runner.class);

        @Inject
        public Runner(KeyStoreAccess keyStoreAccess, @Alias String injectedValue) {
            logger.info("VALUE VIA SERVICE: {}", keyStoreAccess.getEntry(alias));
            logger.info("INJECTED VALUE: {}", injectedValue);
        }

        @Override
        protected void doStart() {
            var thread = new Thread(() -> System.exit(0));
            thread.setDaemon(true);
            thread.start();
        }
    }

}