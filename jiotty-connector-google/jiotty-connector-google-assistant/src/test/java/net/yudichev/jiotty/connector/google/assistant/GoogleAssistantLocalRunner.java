package net.yudichev.jiotty.connector.google.assistant;

import com.google.assistant.embedded.v1alpha2.AudioOutConfig;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.CompletableFutures;
import net.yudichev.jiotty.connector.google.common.GoogleApiAuthSettings;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorizationModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class GoogleAssistantLocalRunner {
    private static final Logger logger = LogManager.getLogger(GoogleAssistantLocalRunner.class);

    static void main(String[] args) {
        Application.builder()
                   .addModule(ExecutorModule::new)
                   .addModule(() -> GoogleAuthorizationModule
                           .builder()
                           .setSettings(GoogleApiAuthSettings.builder()
                                                             .setAuthDataStoreRootDir(Paths.get(System.getProperty("user.home"))
                                                                                           .resolve(".automator")
                                                                                           .resolve("googletokens"))
                                                             .setLocalReceiverHostName("localhost")
                                                             .setApplicationName("Home Automator")
                                                             .setCredentialsUrl(literally(getAsUnchecked(() -> Paths.get(args[0])
                                                                                                                    .toUri()
                                                                                                                    .toURL())))
                                                             .build())
                           .addRequiredScopes(AssistantScopes.SCOPE_ASSISTANT)
                           .build())
                   .addModule(() -> GoogleAssistantClientModule
                           .builder()
                           .withAudioOutConfig(literally(AudioOutConfig.newBuilder()
                                                                       .setEncoding(AudioOutConfig.Encoding.MP3)
                                                                       .setSampleRateHertz(16000)
                                                                       .setVolumePercentage(100)
                                                                       .build()))
                           .build())
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
        private final GoogleAssistantClient client;
        private Thread thread;

        @Inject
        public Runner(GoogleAssistantClient client) {
            this.client = checkNotNull(client);
        }

        @Override
        protected void doStart() {
            thread = new Thread(() -> {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = getAsUnchecked(reader::readLine)) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        client.assist(line).thenAccept(this::playSound).whenComplete(CompletableFutures.logErrorOnFailure(logger, "Assist failed"));
                    }
                }
            });
            thread.start();
        }

        @Override
        protected void doStop() {
            if (thread != null) {
                thread.interrupt();
            }
        }

        public void playSound(byte[] bytes) {
        }
    }
}
