package net.yudichev.jiotty.appliance;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.rest.RestServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.RestServers.createBadRequestJson;
import static net.yudichev.jiotty.common.rest.RestServers.withErrorsHandledJson;

final class ApplianceServer extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(ApplianceServer.class);

    private final Appliance appliance;

    @Inject
    ApplianceServer(@Dependency RestServer restServer,
                    @ApplianceId String applianceId,
                    @Dependency Appliance appliance) {
        this.appliance = checkNotNull(appliance);
        checkNotNull(restServer);
        checkNotNull(applianceId);
        appliance.getAllSupportedCommandMetadata().forEach(commandMeta -> {
            String url = urlFor(applianceId, commandMeta.commandName());
            logger.info("[{}] registering {}", applianceId, url);
            restServer.post(url,
                            context -> {
                                CompletableFuture<?> result;
                                try {
                                    Command<?> command;
                                    try {
                                        command = createCommand(commandMeta, context.req());
                                    } catch (IllegalArgumentException e) {
                                        // Both steps of command construction report an invalid argument this way — decoding by normalising whatever a decoder
                                        // throws, the factory by the contract on [CommandMeta#commandFactory] — and the argument is always the caller's.
                                        String message = humanReadableMessage(e);
                                        logger.info("[{}] rejecting {}: {}", applianceId, url, message);
                                        context.result(createBadRequestJson(context.res(), message));
                                        return;
                                    }
                                    logger.info("[{}] executing {}", applianceId, command);
                                    result = appliance.execute(command);
                                    result.whenComplete((r, throwable) -> logger.info("[{}] executed {}, result: {}", applianceId, command, r, throwable));
                                } catch (RuntimeException e) {
                                    // A command factory that breaks for its own reasons, and an appliance that fails a command on this thread, are both
                                    // internal faults, so they take the same opaque path as a failure the future reports.
                                    result = failure(e);
                                }
                                context.result(withErrorsHandledJson(url, context.res(), result));
                            });
        });
    }

    @Override
    public String name() {
        return String.format("Server for %s @ %s", appliance.name(), System.identityHashCode(this));
    }

    @VisibleForTesting
    static String urlFor(String applianceId, String commandName) {
        return "/appliance/" + applianceId + "/" + commandName.toLowerCase();
    }

    private static Command<?> createCommand(CommandMeta<?> commandMeta, HttpServletRequest request) {
        return commandMeta.createCommand(decodeParameters(commandMeta, request));
    }

    /// Decodes every parameter the command declares, eagerly, so that reading the request is a step of its own: the caller tells a request fault from an
    /// internal one by which step raised it.
    private static Map<String, Object> decodeParameters(CommandMeta<?> commandMeta, HttpServletRequest request) {
        Map<String, CommandParamType> parameterTypes = commandMeta.parameterTypes();
        var parameters = ImmutableMap.<String, Object>builderWithExpectedSize(parameterTypes.size());
        parameterTypes.forEach((name, paramType) -> {
            var value = request.getParameter(name);
            checkArgument(value != null, "Missing required parameter '%s'", name);
            try {
                parameters.put(name, paramType.decode(value));
            } catch (RuntimeException e) {
                // The only input here is the value the caller sent, so whatever a decoder throws — [java.time.format.DateTimeParseException] as readily as
                // [NumberFormatException] — becomes the argument fault it is.
                throw new IllegalArgumentException("Failed decoding parameter " + name, e);
            }
        });
        return parameters.build();
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    @interface ApplianceId {
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    @interface Dependency {
    }
}
