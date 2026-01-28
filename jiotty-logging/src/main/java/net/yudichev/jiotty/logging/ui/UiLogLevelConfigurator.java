package net.yudichev.jiotty.logging.ui;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;
import net.yudichev.jiotty.user.ui.OptionMeta;
import net.yudichev.jiotty.user.ui.TextAreaOption;
import net.yudichev.jiotty.user.ui.UIServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

public final class UiLogLevelConfigurator extends BaseLifecycleComponent {
    private static final Logger logger = LoggerFactory.getLogger(UiLogLevelConfigurator.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final UIServer uiServer;
    private final LoggingLevelConfigurator loggingLevelConfigurator;
    private final ExecutorFactory executorFactory;
    private SchedulingExecutor executor;

    private Map<String, String> levels;

    @Inject
    public UiLogLevelConfigurator(UIServer uiServer, LoggingLevelConfigurator loggingLevelConfigurator, ExecutorFactory executorFactory) {
        this.uiServer = checkNotNull(uiServer);
        this.loggingLevelConfigurator = checkNotNull(loggingLevelConfigurator);
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("log-level-config");
        uiServer.registerOption(new TextAreaOption(executor,
                                                   new OptionMeta<>("Misc", "customLogLevels", "Custom Logging Levels", readLevelsInTextAreaFormat())) {
            {
                rowCount = 10;
            }

            @Override
            public String onChanged() {
                Map<String, String> newLevels = new HashMap<>();
                for (String line : getTrimmedNonBlankLines()) {
                    String[] tokens = WHITESPACE.split(line);
                    checkArgument(tokens.length == 2,
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
        closeSafelyIfNotNull(logger, executor);
    }

    @SuppressWarnings("HardcodedLineSeparator") // textarea
    private String readLevelsInTextAreaFormat() {
        return loggingLevelConfigurator.getLevelsByLoggerName().entrySet().stream()
                                       .map(entry -> entry.getKey() + ' ' + entry.getValue())
                                       .collect(joining("\n"));
    }
}
