package net.yudichev.jiotty.common.testutil;

import com.google.common.collect.ImmutableList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.async.AsyncLoggerConfig;
import org.apache.logging.log4j.core.async.AsyncLoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.System.lineSeparator;
import static org.junit.jupiter.api.Assertions.fail;

/// Watches the chosen logger trees for lines carrying personal data — an email address, a VIN, a precise coordinate — and fails the run when one appears. A
/// project opts in by subclassing with the trees it owns and registering the subclass in `META-INF/services/org.junit.jupiter.api.extension.Extension`, with
/// `junit.jupiter.extensions.autodetection.enabled=true` in its surefire configuration.
///
/// @implNote a violation fails whichever test's `afterEach` drains it, which under concurrent classes can be a different test from the one that logged the
/// line — hence the logger and statement in the message. A line logged after the run's final `afterEach` goes unreported.
public class PiiLogGuardInstaller implements BeforeAllCallback, AfterEachCallback {
    /// JUnit calls `beforeAll` on several of its executor's threads at once where a module runs test classes concurrently, and the install happens once.
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private final List<String> guardedLoggerTrees;

    protected PiiLogGuardInstaller(List<String> guardedLoggerTrees) {
        this.guardedLoggerTrees = ImmutableList.copyOf(checkNotNull(guardedLoggerTrees, "guardedLoggerTrees"));
        checkState(!this.guardedLoggerTrees.isEmpty(), "at least one guarded logger tree is required");
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        if (INSTALLED.compareAndSet(false, true)) {
            install();
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Set<String> violations = PiiLogGuard.drainViolations();
        if (!violations.isEmpty()) {
            fail("Personal data reached the logs during this run:" + lineSeparator()
                 + String.join(lineSeparator(), violations.stream().map(violation -> "  - " + violation).toList()) + lineSeparator()
                 + "Redact it in the value's own formatTo, or at the call site via LogRedaction. Where test classes run concurrently the line may have come "
                 + "from a test other than the one this failure is attached to — the logger and message above identify the statement.");
        }
    }

    /// Attaches the guard to the guarded trees, raising them to [Level#DEBUG] so their lines render for it to read.
    ///
    /// @implNote builds pin `log4j.root.level` to [Level#OFF] (`jiotty-parent`'s surefire configuration), which is why the raise is needed and why
    /// [#takeOverPropagation] caps the console at the level each tree was configured with. A run that asked for logs (`-Dtest.log.level=DEBUG`) finds the
    /// trees at [Level#DEBUG] and leaves both alone.
    void install() {
        var context = (LoggerContext) LogManager.getContext(false);
        // The guard reports through the test that drains it, so it has to record before that test ends: an asynchronous logger would hand the line to a
        // background thread and make the report arrive whenever it arrives. Keep test logging synchronous.
        checkState(!(context instanceof AsyncLoggerContext), "the PII log guard requires synchronous logging, but log4j is using %s", context.getClass());
        Configuration configuration = context.getConfiguration();
        List<LoggerConfig> guardedConfigs = guardedLoggerTrees.stream().map(tree -> loggerConfigFor(configuration, tree)).toList();
        guardedConfigs.forEach(loggerConfig -> checkState(!(loggerConfig instanceof AsyncLoggerConfig),
                                                          "the PII log guard requires synchronous logging, but logger %s is asynchronous",
                                                          loggerConfig.getName()));
        var guard = new PiiLogGuard();
        guard.start();
        guardedConfigs.forEach(loggerConfig -> {
            if (loggerConfig.getLevel().compareTo(Level.DEBUG) < 0) {
                takeOverPropagation(loggerConfig);
                loggerConfig.setLevel(Level.DEBUG);
            }
            loggerConfig.addAppender(guard, null, null);
        });
        context.updateLoggers();
    }

    /// The configuration for `tree` exactly, adding one that inherits the level it would otherwise have resolved to when the module configures no such logger.
    private static LoggerConfig loggerConfigFor(Configuration configuration, String tree) {
        LoggerConfig inherited = configuration.getLoggerConfig(tree);
        if (inherited.getName().equals(tree)) {
            return inherited;
        }
        var loggerConfig = new LoggerConfig(tree, inherited.getLevel(), true);
        configuration.addLogger(tree, loggerConfig);
        return loggerConfig;
    }

    /// Gives `loggerConfig` every appender it reaches, each capped at the level it was configured with, and makes it the end of its own propagation chain.
    /// Raising its level afterwards then changes what the guard sees and leaves the console where it was.
    private static void takeOverPropagation(LoggerConfig loggerConfig) {
        Level configuredLevel = loggerConfig.getLevel();
        // Copied because the loop re-attaches each appender, mutating the map it is walking.
        List.copyOf(loggerConfig.getAppenders().values()).forEach(appender -> {
            loggerConfig.removeAppender(appender.getName());
            loggerConfig.addAppender(appender, configuredLevel, null);
        });
        if (loggerConfig.isAdditive()) {
            for (LoggerConfig ancestor = loggerConfig.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                ancestor.getAppenders().values().forEach(appender -> loggerConfig.addAppender(appender, configuredLevel, null));
                if (!ancestor.isAdditive()) {
                    break;
                }
            }
        }
        loggerConfig.setAdditive(false);
    }
}
