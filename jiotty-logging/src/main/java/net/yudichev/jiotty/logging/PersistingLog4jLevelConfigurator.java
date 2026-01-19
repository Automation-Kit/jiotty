package net.yudichev.jiotty.logging;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableMap;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import net.yudichev.jiotty.common.varstore.VarStore;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


public final class PersistingLog4jLevelConfigurator extends BaseLifecycleComponent implements LoggingLevelConfigurator {
    private static final Logger log = LoggerFactory.getLogger(PersistingLog4jLevelConfigurator.class);

    private final VarStore varStore;
    private final String storeKey;

    private final Map<String, String> levelsByLoggerName = new TreeMap<>();

    @Inject
    public PersistingLog4jLevelConfigurator(VarStore varStore, @VarStoreKeyPrefix String varStoreKeyPrefix) {
        this.varStore = checkNotNull(varStore);
        storeKey = varStoreKeyPrefix.isEmpty() ? "LogLevels" : varStoreKeyPrefix + '_' + "LogLevels";
    }

    @Override
    protected void doStart() {
        varStore.readValue(LoggerLevels.class, storeKey)
                .orElse(LoggerLevels.builder().build())
                .getLevelsByLoggerName()
                .forEach(this::doSetLoggingLevel);
        // re-save in case store was modified to contain invalid data
        if (!levelsByLoggerName.isEmpty()) {
            varStore.saveValue(storeKey, LoggerLevels.of(levelsByLoggerName));
        }
    }

    @Override
    protected void doStop() {
        synchronized (levelsByLoggerName) {
            resetAllLoggingLevels();
            levelsByLoggerName.clear();
        }
    }

    @Override
    public void setLoggingLevel(String loggerName, String logLevel) {
        LoggerLevels loggerLevels;
        synchronized (levelsByLoggerName) {
            doSetLoggingLevel(loggerName, logLevel);
            loggerLevels = LoggerLevels.of(levelsByLoggerName);
        }
        varStore.saveValue(storeKey, loggerLevels);
    }

    @Override
    public void setLoggingLevels(Map<String, String> levelsByLoggerName) {
        LoggerLevels loggerLevels;
        synchronized (this.levelsByLoggerName) {
            resetAllLoggingLevels();
            this.levelsByLoggerName.clear();
            levelsByLoggerName.forEach(this::doSetLoggingLevel);
            loggerLevels = LoggerLevels.of(this.levelsByLoggerName);
        }
        varStore.saveValue(storeKey, loggerLevels);
    }

    private void resetAllLoggingLevels() {
        levelsByLoggerName.keySet().forEach(name -> Configurator.setLevel(name, (Level) null));
        log.info("Reset all logging levels");
    }

    private void doSetLoggingLevel(String loggerName, String logLevel) {
        var level = Level.toLevel(logLevel);
        Configurator.setLevel(loggerName, level);
        String oldLevel = levelsByLoggerName.put(loggerName, level.name());
        log.info("Set logger {} {} -> {}{}",
                 loggerName, oldLevel, level, level.name().equals(logLevel) ? "" : "(invalid level specified: " + logLevel + ")");
    }

    @Override
    public void resetLoggingLevel(String loggerName) {
        LoggerLevels loggerLevels;
        synchronized (levelsByLoggerName) {
            String oldLevel = levelsByLoggerName.remove(loggerName);
            if (oldLevel != null) {
                Configurator.setLevel(loggerName, (Level) null);
                log.info("Reset logger {} (was set to {})", loggerName, oldLevel);
            }
            loggerLevels = LoggerLevels.of(levelsByLoggerName);
        }
        varStore.saveValue(storeKey, loggerLevels);
    }

    @Override
    public Map<String, String> getLevelsByLoggerName() {
        synchronized (levelsByLoggerName) {
            return ImmutableMap.copyOf(levelsByLoggerName);
        }
    }

    @Immutable
    @PackagePrivateImmutablesStyle
    @JsonSerialize
    @JsonDeserialize
    interface BaseLoggerLevels {
        @Value.Parameter
        Map<String, String> getLevelsByLoggerName();
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface VarStoreKeyPrefix {
    }
}