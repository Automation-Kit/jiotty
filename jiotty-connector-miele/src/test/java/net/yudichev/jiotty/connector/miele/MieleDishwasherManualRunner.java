package net.yudichev.jiotty.connector.miele;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.common.varstore.VarStoreModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

@SuppressWarnings({"OverlyBroadCatchBlock", "DynamicRegexReplaceableByCompiledPattern", "UseOfSystemOutOrSystemErr", "OverlyNestedMethod"})
final class MieleDishwasherManualRunner {
    static void main(String[] args) {
        Application.builder()
                   .addModule(TimeModule::new)
                   .addModule(ExecutorModule::new)
                   .addModule(() -> VarStoreModule.builder().setPath(Paths.get(args[0], "varstore.json")).build())
                   .addModule(() -> MieleModule.builder()
                                               .setDeviceId(literally(args[1]))
                                               .setClientId(literally(args[2]))
                                               .setClientSecret(literally(args[3]))
                                               .build())
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(CmdLineTest.class);
                       }
                   })
                   .build()
                   .run();
    }

    private static class CmdLineTest extends BaseLifecycleComponent {
        private final MieleDishwasher dishwasher;

        @Inject
        public CmdLineTest(MieleDishwasher dishwasher) {
            this.dishwasher = dishwasher;
        }

        @Override
        protected void doStart() {
            dishwasher.subscribeToEvents(event -> System.out.println("event: " + event));
            new Thread(() -> {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = MoreThrowables.getAsUnchecked(reader::readLine)) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        try {
                            String[] cmd = line.split("\\W+");
                            var future = switch (cmd[0]) {
                                case "on" -> dishwasher.powerOn();
                                case "off" -> dishwasher.powerOff();
                                case "p" -> dishwasher.startProgram(Integer.parseInt(cmd[1]));
                                case "ga" -> dishwasher.getActions();
                                case "gp" -> dishwasher.getPrograms();
                                default -> {
                                    System.err.println("What is this - " + Arrays.toString(cmd));
                                    yield CompletableFuture.completedFuture(null);
                                }
                            };
                            Object result = future.get(20, TimeUnit.SECONDS);
                            System.out.println(result == null ? "Done" : "Result: " + result);
                        } catch (Exception e) {
                            System.out.println("Failure: " + humanReadableMessage(e));
                        }
                    }
                }
            }).start();
        }
    }
}