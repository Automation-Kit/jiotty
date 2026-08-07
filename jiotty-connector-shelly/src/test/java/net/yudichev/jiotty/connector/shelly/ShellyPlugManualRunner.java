package net.yudichev.jiotty.connector.shelly;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.common.time.TimeModule;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.connector.shelly.ShellyPlug.ConsumptionMeasurement;

@SuppressWarnings({"ThrowCaughtLocally", "DynamicRegexReplaceableByCompiledPattern", "UseOfSystemOutOrSystemErr", "OverlyNestedMethod", "CallToSystemExit"})
final class ShellyPlugManualRunner {

    static void main(String[] args) {
        Application.builder()
                   .addModule(() -> TimeModule.builder().build())
                   .addModule(() -> ExecutorModule.builder().build())
                   .addModule(() -> ShellyPlugModule.builder()
                                                    .setHost(literally(args[0]))
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
        private final ShellyPlug plug;

        @Inject
        public CmdLineTest(ShellyPlug plug) {
            this.plug = plug;
        }

        @Override
        protected void doStart() {
            new Thread(() -> {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                ConsumptionMeasurement consumptionMeasurement = null;
                while ((line = MoreThrowables.getAsUnchecked(reader::readLine)) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        try {
                            String[] cmd = line.split("\\W+");
                            String result = switch (cmd[0]) {
                                case "start" -> {
                                    consumptionMeasurement = plug.startMeasuringConsumption(System.err::println);
                                    yield "Started";
                                }
                                case "stop" -> consumptionMeasurement == null ? "Not started" : "Result: " + consumptionMeasurement.stop();
                                case "exit" -> {
                                    System.exit(0);
                                    yield null;
                                }
                                default -> throw new RuntimeException("What is this - " + Arrays.toString(cmd));
                            };
                            System.out.println(result);
                        } catch (RuntimeException e) {
                            System.out.println("Failure: " + humanReadableMessage(e));
                        }
                    }
                }
            }).start();
        }
    }
}