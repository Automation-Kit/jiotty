package net.yudichev.jiotty.connector.pushover;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.keystore.KeyStoreAccessModule;

import java.nio.file.Paths;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.keystore.KeyStoreEntryModule.keyStoreEntry;

@SuppressWarnings("CallToSystemGetenv")
final class PushoverLocalRunner {
    private static String userToken;

    @SuppressWarnings("UnusedAssignment")
    static void main(String[] args) {
        int i = 0;
        String keyStorePass = checkNotNull(System.getenv("AUTOMATOR_KEYSTORE_PASS"));
        String pathToKeyStore = args[i++];
        userToken = args[i++];
        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> KeyStoreAccessModule.builder()
                                                        .setPathToKeystore(literally(pathToKeyStore).map(new TypeToken<>() {},
                                                                                                         new TypeToken<>() {},
                                                                                                         Paths::get))
                                                        .setKeystorePass(literally(keyStorePass))
                                                        .build())
                   .addModule(() -> PushoverUserAlerterModule.builder().setApiToken(keyStoreEntry("pushover-api-token")).build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(Runner.class);
                       }
                   })
                   .build()
                   .run();
    }

    static class Runner extends BaseLifecycleComponent {
        private final UserAlerter userAlerter;
        private Thread thread;

        @Inject
        public Runner(UserAlerter userAlerter) {
            this.userAlerter = checkNotNull(userAlerter);
        }

        @Override
        protected void doStart() {
            thread = new Thread(() -> userAlerter.sendAlert(() -> userToken, MessagePriority.NORMAL, "Ze Alert"));
            thread.start();
        }

        @Override
        protected void doStop() {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
