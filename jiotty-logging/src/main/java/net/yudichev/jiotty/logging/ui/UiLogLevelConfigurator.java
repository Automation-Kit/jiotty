package net.yudichev.jiotty.logging.ui;

import com.google.common.base.Preconditions;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;
import net.yudichev.jiotty.user.ui.UIServer;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.TextAreaOption;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

public final class UiLogLevelConfigurator extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(UiLogLevelConfigurator.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final UIServer uiServer;
    private final LoggingLevelConfigurator loggingLevelConfigurator;
    private final ExecutorFactory executorFactory;
    private SchedulingExecutor executor;

    private Closeable optionRegistration;
    private Map<String, String> levels;

    @Inject
    public UiLogLevelConfigurator(@Dependency UIServer uiServer,
                                  @Dependency LoggingLevelConfigurator loggingLevelConfigurator,
                                  ExecutorFactory executorFactory) {
        this.uiServer = checkNotNull(uiServer);
        this.loggingLevelConfigurator = checkNotNull(loggingLevelConfigurator);
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("log-level-config");
        optionRegistration = uiServer.registerOption(new TextAreaOption(executor,
                                                                        OptionMeta.<String>builder()
                                                                                  .setTabName("Misc")
                                                                                  .setKey("customLogLevels")
                                                                                  .setLabel("Custom Logging Levels")
                                                                                  .setDefaultValue(readLevelsInTextAreaFormat())
                                                                                  .build()) {
            {
                rowCount = 10;
            }

            @Override
            public String onChanged() {
                Map<String, String> newLevels = new HashMap<>();
                for (String line : getTrimmedNonBlankLines()) {
                    String[] tokens = WHITESPACE.split(line);
                    Preconditions.checkArgument(tokens.length == 2,
                                                "Line '%s' must contain logger name and log level separated by whitespace", line);
                    newLevels.put(tokens[0], tokens[1]);
                }
                if (!newLevels.equals(levels)) {
                    levels = newLevels;
                    loggingLevelConfigurator.setLoggingLevels(newLevels);
                }
                return readLevelsInTextAreaFormat();
            }
        });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, optionRegistration, executor);
    }

    @SuppressWarnings("HardcodedLineSeparator") // textarea
    private String readLevelsInTextAreaFormat() {
        return loggingLevelConfigurator.getLevelsByLoggerName().entrySet().stream()
                                       .map(entry -> entry.getKey() + ' ' + entry.getValue())
                                       .collect(joining("\n"));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
