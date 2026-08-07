package net.yudichev.jiotty.connector.sonyprojector;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.MoreThrowables;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "OverlyNestedMethod", "CallToSystemExit", "DynamicRegexReplaceableByCompiledPattern"})
final class ManualSonyProjectorClientTest {
    static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: ManualSonyProjectorClientTest <host> <passwordOrDash>");
            System.exit(2);
        }

        String host = args[0];
        String password = "-".equals(args[1]) ? null : args[1];

        Application.builder()
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> {
                       var builder = SonyProjectorClientModule.builder()
                                                              .setHost(literally(host));
                       if (password != null) {
                           builder.withPassword(literally(password));
                       }
                       return builder.build();
                   })
                   .addModule(() -> new BaseLifecycleComponentModule() {
                       @Override
                       protected void configure() {
                           registerLifecycleComponent(CmdLineTest.class);
                       }
                   })
                   .build()
                   .run();
    }

    @SuppressWarnings("OverlyBroadCatchBlock")
    private static class CmdLineTest extends BaseLifecycleComponent {
        private final SonyProjectorClient client;

        @Inject
        CmdLineTest(SonyProjectorClient client) {
            this.client = client;
        }

        @Override
        protected void doStart() {
            new Thread(() -> {
                System.out.println("Commands: on, off, status, exit");
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = MoreThrowables.getAsUnchecked(reader::readLine)) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        try {
                            var future = command(line);
                            Object result = future.get(20, SECONDS);
                            System.out.println(result == null ? "Done" : "Result: " + result);
                        } catch (Exception e) {
                            System.out.println("Failure: " + humanReadableMessage(e));
                        }
                    }
                }
            }).start();
        }

        private CompletableFuture<?> command(String line) {
            String[] cmd = line.split("\\W+");
            return switch (cmd[0]) {
                case "on" -> client.powerOn();
                case "off" -> client.powerOff();
                case "status" -> client.getPowerState();
                case "exit" -> {
                    System.exit(0);
                    yield CompletableFuture.completedFuture(null);
                }
                default -> {
                    System.err.println("Unknown command: " + Arrays.toString(cmd));
                    yield CompletableFuture.completedFuture(null);
                }
            };
        }
    }
}
