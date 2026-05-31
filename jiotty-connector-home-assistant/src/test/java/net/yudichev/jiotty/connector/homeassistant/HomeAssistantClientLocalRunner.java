package net.yudichev.jiotty.connector.homeassistant;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.Application;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import org.jspecify.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class HomeAssistantClientLocalRunner {
    static void main(String[] args) {
        Application.builder()
                   .addModule(() -> HomeAssistantClientModule
                           .builder()
                           .setBaseUrl(literally(args[0]))
                           .setAccessToken(literally(args[1]))
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
        public static final Pattern PATTERN = Pattern.compile(
                "^\\s*(\\S+)\\s+(\\S+)(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?(?:\\s+(\\S+))?\\s*$");
        private final HomeAssistantClient client;
        private Thread thread;

        @Inject
        public Runner(HomeAssistantClient client) {
            this.client = checkNotNull(client);
        }

        @SuppressWarnings({"UseOfSystemOutOrSystemErr", "OverlyNestedMethod", "CallToPrintStackTrace"})
        @Override
        protected void doStart() {
            thread = new Thread(() -> {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                String line;
                while ((line = getAsUnchecked(reader::readLine)) != null) {
                    var matcher = PATTERN.matcher(line);
                    if (matcher.matches()) {
                        var arg1 = matcher.group(1);
                        var arg2 = matcher.group(2);
                        try {
                            CompletableFuture<?> result;
                            if ("log".equals(arg1)) {
                                result = handleLog(arg2, matcher.group(3));
                            } else if ("hist".equals(arg1)) {
                                result = handleHistory(arg2, matcher.group(3));
                            } else {
                                var entity = matcher.group(3);
                                if ("get".equals(arg2)) {
                                    result = handleGetState(arg1, entity);
                                } else {
                                    result = handleSetState(arg1, arg2, entity, matcher);
                                }
                            }
                            System.out.println("Awaiting Result...");
                            System.out.println("Result: " + getAsUnchecked(result::get));
                        } catch (RuntimeException e) {
                            e.printStackTrace();
                        }
                    } else {
                        System.err.println("Unparseable command: " + line);
                    }
                }
            });
            thread.start();
        }

        private CompletableFuture<?> handleLog(String entityId, @Nullable String fromTo) {
            var fromAndTo = parseFromAndTo(fromTo);
            return client.logBook().get(entityId, fromAndTo.from(), fromAndTo.to());
        }

        private CompletableFuture<?> handleHistory(String entityId, @Nullable String fromTo) {
            var fromAndTo = parseFromAndTo(fromTo);
            return client.history().get(Arrays.asList(entityId.split(",")), fromAndTo.from(), fromAndTo.to());
        }

        private static FromAndTo parseFromAndTo(@Nullable String fromTo) {
            Optional<Instant> from = Optional.empty();
            Optional<Instant> to = Optional.empty();
            if (fromTo != null) {
                String[] fromToArray = fromTo.split("/", -1);
                checkArgument(fromToArray.length == 2, "FromTo arg must be in form from/to");
                if (!fromToArray[0].isEmpty()) {
                    from = Optional.of(Instant.parse(fromToArray[0]));
                }
                if (!fromToArray[1].isEmpty()) {
                    to = Optional.of(Instant.parse(fromToArray[1]));
                }
            }
            return new FromAndTo(from, to);
        }

        @SuppressWarnings({"OverlyComplexMethod", "OverlyLongMethod", "NestedSwitchStatement", "SwitchStatementWithTooFewBranches"})
        private CompletableFuture<?> handleSetState(String domain, String command, String entity, MatchResult matcher) {
            CompletableFuture<?> result;
            result = switch (domain) {
                case "climate" -> switch (command) {
                    case "on" -> client.climate().turnOn("climate." + entity);
                    case "off" -> client.climate().turnOff("climate." + entity);
                    case "hvac" -> client.climate().setHvacMode("climate." + entity, matcher.group(4));
                    default -> throw new IllegalArgumentException("Unrecognised climate command: " + command);
                };
                case "number" -> {
                    double value = Double.parseDouble(matcher.group(4));
                    yield switch (command) {
                        case "set" -> client.number().setValue("number." + entity, value);
                        default -> throw new IllegalArgumentException("Unrecognised number command: " + command);
                    };
                }
                case "switch" -> switch (command) {
                    case "on" -> client.aSwitch().turnOn("switch." + entity);
                    case "off" -> client.aSwitch().turnOff("switch." + entity);
                    default -> throw new IllegalArgumentException("Unrecognised switch command: " + command);
                };
                case "button" -> switch (command) {
                    case "press" -> client.button().press("switch." + entity);
                    default -> throw new IllegalArgumentException("Unrecognised switch command: " + command);
                };
                default -> throw new IllegalArgumentException("Unrecognised domain: " + domain);
            };
            return result;
        }

        private CompletableFuture<?> handleGetState(String domain, String entity) {
            CompletableFuture<?> result;
            result = (switch (domain) {
                case "climate" -> client.climate().getState(entity);
                case "number" -> client.number().getState(entity);
                case "switch" -> client.aSwitch().getState(entity);
                case "button" -> client.button().getState(entity);
                case "sensor" -> client.sensor().getState(entity);
                case "bsensor" -> client.binarySensor().getBinaryState(entity);
                case "loc" -> client.deviceTracker().getState(entity);
                default -> throw new IllegalArgumentException("Unrecognised domain: " + domain);
            });
            return result;
        }

        @Override
        protected void doStop() {
            if (thread != null) {
                thread.interrupt();
            }
        }

        private record FromAndTo(Optional<Instant> from, Optional<Instant> to) {}
    }
}