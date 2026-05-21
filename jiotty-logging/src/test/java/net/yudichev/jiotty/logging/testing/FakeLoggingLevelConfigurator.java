package net.yudichev.jiotty.logging.testing;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.logging.LoggingLevelConfigurator;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

/// In-memory [LoggingLevelConfigurator] for tests. Records calls in a `Map` keyed by logger name and never touches log4j, so integration tests cannot
/// accidentally reconfigure the JVM's actual logging.
public final class FakeLoggingLevelConfigurator implements LoggingLevelConfigurator {
    private final Map<String, String> levelsByLoggerName = new HashMap<>();

    @Override
    public void setLoggingLevel(String loggerName, String logLevel) {
        levelsByLoggerName.put(checkNotNull(loggerName), checkNotNull(logLevel));
    }

    @Override
    public void setLoggingLevels(Map<String, String> levelsByLoggerName) {
        this.levelsByLoggerName.clear();
        this.levelsByLoggerName.putAll(checkNotNull(levelsByLoggerName));
    }

    @Override
    public void resetLoggingLevel(String loggerName) {
        levelsByLoggerName.remove(checkNotNull(loggerName));
    }

    @Override
    public Map<String, String> getLevelsByLoggerName() {
        return ImmutableMap.copyOf(levelsByLoggerName);
    }
}
