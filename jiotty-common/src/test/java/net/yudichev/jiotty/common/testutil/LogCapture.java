package net.yudichev.jiotty.common.testutil;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.google.common.base.Preconditions.checkNotNull;

/// Captures the lines one logger renders, for a test asserting on what it logged. The build pins the root level to [Level#OFF], so the capture installs a
/// logger config of its own, non-additive so the lines go there alone; [#close()] removes it.
public final class LogCapture implements AutoCloseable {
    /// Written on whichever thread logs and drained on the JUnit thread that asserts.
    private final Queue<CapturedLine> lines = new ConcurrentLinkedQueue<>();
    private final LoggerContext loggerContext;
    private final String loggerName;
    private final CapturingAppender appender;

    private LogCapture(String loggerName, Level level) {
        this.loggerName = loggerName;
        loggerContext = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender(lines);
        appender.start();
        var loggerConfig = new LoggerConfig(loggerName, level, false);
        loggerConfig.addAppender(appender, null, null);
        loggerContext.getConfiguration().addLogger(loggerName, loggerConfig);
        loggerContext.updateLoggers();
    }

    /// Starts capturing what the logger named after `loggerOwner` logs at `level` or above.
    public static LogCapture capture(Class<?> loggerOwner, Level level) {
        return new LogCapture(loggerOwner.getName(), level);
    }

    /// @return the lines captured so far, in the order they were logged
    public List<CapturedLine> lines() {
        return ImmutableList.copyOf(lines);
    }

    @Override
    public void close() {
        loggerContext.getConfiguration().removeLogger(loggerName);
        loggerContext.updateLoggers();
        appender.stop();
    }

    /// One rendered log line.
    public record CapturedLine(Level level, String message) {
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final Queue<CapturedLine> lines;

        CapturingAppender(Queue<CapturedLine> lines) {
            super("capturingAppender", null, null, true, Property.EMPTY_ARRAY);
            this.lines = checkNotNull(lines);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(new CapturedLine(event.getLevel(), event.getMessage().getFormattedMessage()));
        }
    }
}
