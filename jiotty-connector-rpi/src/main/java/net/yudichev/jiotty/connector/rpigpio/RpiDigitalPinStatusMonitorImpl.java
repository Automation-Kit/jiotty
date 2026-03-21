package net.yudichev.jiotty.connector.rpigpio;

import com.google.inject.BindingAnnotation;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalInput;
import com.pi4j.io.gpio.digital.DigitalInputProvider;
import com.pi4j.io.gpio.digital.DigitalState;
import com.pi4j.io.gpio.digital.PullResistance;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;

final class RpiDigitalPinStatusMonitorImpl extends BaseLifecycleComponent implements RpiDigitalPinStatusMonitor {
    private static final Logger logger = LogManager.getLogger(RpiDigitalPinStatusMonitorImpl.class);

    private final Provider<Context> pi4jContextProvider;
    private final Integer pin;
    private final PullResistance pullResistance;
    private final Set<Consumer<DigitalState>> listeners = new CopyOnWriteArraySet<>();

    private Closeable closeable;
    private DigitalInput input;

    @Inject
    RpiDigitalPinStatusMonitorImpl(Provider<Context> pi4jContextProvider,
                                   @Pin Integer pin,
                                   @Dependency PullResistance pullResistance) {
        this.pi4jContextProvider = checkNotNull(pi4jContextProvider);
        this.pin = checkNotNull(pin);
        this.pullResistance = checkNotNull(pullResistance);
    }

    @Override
    public Closeable addListener(Consumer<DigitalState> listener) {
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(listeners.add(listener), "already added: %s", listener);
            listener.accept(input.state());
            return Closeable.idempotent(() -> listeners.remove(listener));
        });
    }

    @Override
    protected void doStart() {
        Context pi4jContext = pi4jContextProvider.get();
        DigitalInputProvider digitalInputProvider = pi4jContext.provider("gpiod-digital-input");
        input = digitalInputProvider.create(DigitalInput.newConfigBuilder(pi4jContext)
                                                        .address(pin)
                                                        .pull(pullResistance)
                                                        .build());
        input.addListener(event -> {
            logger.info("{}", event);
            onListenerStateChange(event.state());
        });
        closeable = Closeable.idempotent(() -> input.shutdown(pi4jContext));
    }

    @Override
    protected void doStop() {
        closeable.close();
    }

    private void onListenerStateChange(DigitalState state) {
        runForAll(listeners, pinStateConsumer -> pinStateConsumer.accept(state));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Pin {
    }
}
