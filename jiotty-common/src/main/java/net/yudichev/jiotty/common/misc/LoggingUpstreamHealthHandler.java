package net.yudichev.jiotty.common.misc;

import com.google.common.annotations.VisibleForTesting;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/// Default [UpstreamHealthHandler]: logs the named upstream entering and leaving sustained failure, for hosts that surface upstream health through the log
/// alone. One line when calls start failing and one when they recover.
///
/// The default level is INFO, leaving the WARN/ERROR decision to the host application; a host whose operator channel is level-gated (e.g. a WARN-routed
/// mail appender) chooses the level via [#LoggingUpstreamHealthHandler(String, Level)].
public final class LoggingUpstreamHealthHandler implements UpstreamHealthHandler, StringFormattable {
    private final Logger logger;

    private final String upstreamName;
    private final Level level;
    /// Guards [#failing].
    private final Object lock = new Object();
    /// Whether the upstream is currently in sustained failure, so each transition is logged once.
    private boolean failing;

    public LoggingUpstreamHealthHandler(String upstreamName) {
        this(upstreamName, Level.INFO);
    }

    public LoggingUpstreamHealthHandler(String upstreamName, Level level) {
        this(upstreamName, level, LogManager.getLogger(LoggingUpstreamHealthHandler.class));
    }

    @VisibleForTesting
    LoggingUpstreamHealthHandler(String upstreamName, Level level, Logger logger) {
        this.upstreamName = checkNotNull(upstreamName, "upstreamName");
        this.level = checkNotNull(level, "level");
        this.logger = checkNotNull(logger, "logger");
    }

    @Override
    public void onFailure(String message, Throwable cause) {
        // Logged inside the lock so concurrent transitions cannot log out of order — the log is this handler's whole output.
        synchronized (lock) {
            if (!failing) {
                failing = true;
                logger.log(level, "{} failing: {}", upstreamName, message, cause);
            }
        }
    }

    @Override
    public void onSuccess() {
        synchronized (lock) {
            if (failing) {
                failing = false;
                logger.log(level, "{} recovered", upstreamName);
            }
        }
    }

    @Override
    public String toString() {
        return toString(48);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "LoggingUpstreamHealthHandler[");
        Append.to(appendable, upstreamName);
        Append.to(appendable, ']');
    }
}
