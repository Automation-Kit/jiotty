package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.FriendlyDurationFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class InitConfigurationOptionManager<T> extends BaseLifecycleComponent {
    /// Amount of time after the option last changed before the application is restarted.
    static final Duration DEBOUNCE_TIME_BEFORE_RESTART = Duration.ofSeconds(5);
    private static final String DEBOUNCE_TIME_HUMAN_READABLE = FriendlyDurationFormat.formatHuman(DEBOUNCE_TIME_BEFORE_RESTART);
    private static final Logger logger = LoggerFactory.getLogger(InitConfigurationOptionManager.class);

    private final UIServer uiServer;
    private final ExecutorFactory executorFactory;
    private final ApplicationLifecycleControl applicationLifecycleControl;
    private final OptionType type;
    private final OptionMeta<T> optionMeta;

    private SchedulingExecutor executor;
    private boolean initialValueReceived;
    private Closeable restartSchedule;

    @Inject
    public InitConfigurationOptionManager(UIServer uiServer,
                                          ExecutorFactory executorFactory,
                                          ApplicationLifecycleControl applicationLifecycleControl,
                                          @Dependency OptionType type,
                                          @Dependency OptionMeta<T> optionMeta) {
        this.uiServer = checkNotNull(uiServer);
        this.executorFactory = checkNotNull(executorFactory);
        this.applicationLifecycleControl = checkNotNull(applicationLifecycleControl);
        this.type = checkNotNull(type);
        this.optionMeta = checkNotNull(optionMeta);
    }

    @Override
    public String name() {
        return super.name() + " - " + optionMeta.key();
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("InitOption-" + optionMeta.key());
        uiServer.registerOption(type.createInstance(optionMeta, executor, this::onValueChange));
    }

    @Override
    protected void doStop() {
        Closeable.closeIfNotNull(executor);
    }

    private T onValueChange(Option<T> option) {
        T newValue = option.requireValue();
        if (initialValueReceived) {
            logger.info("[{}] value changed -> {}, will restart in {}", optionMeta.key(), newValue, DEBOUNCE_TIME_HUMAN_READABLE);
            checkState(!applicationLifecycleControl.restarting(), "Already restarting");
            Closeable.closeIfNotNull(restartSchedule);
            restartSchedule = executor.schedule(DEBOUNCE_TIME_BEFORE_RESTART, () -> {
                // TODO:commerce "Application" here needs to be a sub-application of the main application, or something along these lines.
                //  obviously not restarting the whole app if one custmer changed one of their own init config options
                logger.info("[{}] option changed and {} passed, restarting application", optionMeta.key(), DEBOUNCE_TIME_HUMAN_READABLE);
                applicationLifecycleControl.initiateRestart();
            });
        } else {
            logger.debug("[{}] initial value received", optionMeta.key());
            initialValueReceived = true;
        }
        return newValue;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
