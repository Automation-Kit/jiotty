package net.yudichev.jiotty.connector.tplinksmartplug;

import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.appliance.Command;
import net.yudichev.jiotty.appliance.CommandMeta;
import net.yudichev.jiotty.appliance.PowerCommand;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.appliance.PowerCommand.OFF;
import static net.yudichev.jiotty.appliance.PowerCommand.ON;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.connector.tplinksmartplug.Bindings.Dependency;
import static net.yudichev.jiotty.connector.tplinksmartplug.Bindings.Name;

/// Based on <a href="https://github.com/ggeorgovassilis/linuxscripts/blob/master/tp-link-hs100-smartplug/hs100.sh">this script</a>.
final class LocalTpLinkSmartPlug extends BaseLifecycleComponent implements Appliance {
    private static final Logger logger = LogManager.getLogger(LocalTpLinkSmartPlug.class);
    private static final Map<Command<?>, byte[]> COMMAND_TO_BINARY_PACKET = ImmutableMap.of(
            // encoded {"system":{"set_relay_state":{"state":1}}}
            ON, Base64.getDecoder().decode("AAAAKtDygfiL/5r31e+UtsWg1Iv5nPCR6LfEsNGlwOLYo4HyhueT9tTu36Lfog=="),
            // encoded {"system":{"set_relay_state":{"state":0}}}
            OFF, Base64.getDecoder().decode("AAAAKtDygfiL/5r31e+UtsWg1Iv5nPCR6LfEsNGlwOLYo4HyhueT9tTu3qPeow==")
    );

    private final String name;
    private final String host;
    private final RetryableOperationExecutor retryableOperationExecutor;
    private final ExecutorFactory executorFactory;
    private SchedulingExecutor executor;

    @Inject
    LocalTpLinkSmartPlug(@Name String name,
                         @Host String host,
                         @Dependency RetryableOperationExecutor retryableOperationExecutor,
                         ExecutorFactory executorFactory) {
        this.name = checkNotNull(name);
        this.host = checkNotNull(host);
        this.retryableOperationExecutor = retryableOperationExecutor;
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    public Set<CommandMeta<?>> getAllSupportedCommandMetadata() {
        return PowerCommand.allPowerCommandMetas();
    }

    @Override
    public CompletableFuture<?> execute(Command<?> command) {
        return whenStartedAndNotLifecycling(() -> {
            //noinspection RedundantTypeArguments compiler is not coping
            return retryableOperationExecutor.withBackOffAndRetry(
                                                     "execute " + command + " for plug " + name,
                                                     () -> command.<CompletableFuture<?>>acceptOrFail((PowerCommand.Visitor<CompletableFuture<?>>) powerCommand ->
                                                             executor.submit(() -> sendToPlug(COMMAND_TO_BINARY_PACKET.get(powerCommand)))))
                                             .thenRun(() -> logger.info("Plug {}: executed {}", name, command));
        });
    }

    private void sendToPlug(byte[] bytes) {
        asUnchecked(() -> {
            try (Socket socket = new Socket(host, 9999); var outputStream = socket.getOutputStream()) {
                outputStream.write(bytes);
                outputStream.flush();
            }
        });
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("tp-link-plug-" + name);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, executor);
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    @interface Host {
    }
}
