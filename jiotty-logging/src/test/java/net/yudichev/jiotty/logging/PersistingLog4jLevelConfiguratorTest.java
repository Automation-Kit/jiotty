package net.yudichev.jiotty.logging;

import net.yudichev.jiotty.common.varstore.InMemoryVarStore;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"StaticVariableUsedBeforeInitialization", "StaticVariableMayNotBeInitialized"}) // static @BeforeAll mechanism used
@Isolated
class PersistingLog4jLevelConfiguratorTest {
    private static final String LOGGER_NAME = PersistingLog4jLevelConfiguratorTest.class.getSimpleName() + "_logger";
    private static final String FILE_APPENDER_NAME = "TEST_FILE";
    private static Path logFile;
    private static InMemoryVarStore varStore;
    private static Logger logger;
    private static PersistingLog4jLevelConfigurator configurator;
    private static int verificationCounter;
    private static FileAppender fileAppender;

    @BeforeAll
    static void setUp() throws IOException {
        logFile = Files.createTempFile(PersistingLog4jLevelConfiguratorTest.class.getSimpleName(), ".log");

        // Dynamically add FILE appender to Log4j configuration
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();

        PatternLayout layout = PatternLayout.newBuilder()
                                            .withPattern("%c %m%n")
                                            .withConfiguration(config)
                                            .build();

        fileAppender = FileAppender.newBuilder()
                                   .setName(FILE_APPENDER_NAME)
                                   .withFileName(logFile.toString())
                                   .withAppend(false)
                                   .setLayout(layout)
                                   .setConfiguration(config)
                                   .build();

        fileAppender.start();
        config.addAppender(fileAppender);

        // Add the appender to the root logger
        LoggerConfig rootLoggerConfig = config.getRootLogger();
        rootLoggerConfig.addAppender(fileAppender, Level.TRACE, null);

        context.updateLoggers();

        logger = LogManager.getLogger(LOGGER_NAME);

        varStore = new InMemoryVarStore();

        startConfigurator();
    }

    @AfterAll
    static void tearDown() throws IOException {
        // Remove the dynamically added FILE appender
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration config = context.getConfiguration();

        LoggerConfig rootLoggerConfig = config.getRootLogger();
        rootLoggerConfig.removeAppender(FILE_APPENDER_NAME);

        config.getAppenders().remove(FILE_APPENDER_NAME);
        fileAppender.stop();

        context.updateLoggers();

        Files.delete(logFile);
    }

    @Test
    void changesLoggingLevel() throws IOException {
        configurator.setLoggingLevel(LOGGER_NAME, "TRACE");
        verifyTraceLevel();
        restartConfigurator();
        verifyTraceLevel();

        configurator.setLoggingLevels(Map.of(LOGGER_NAME, "DEBUG"));
        verifyDebugLevel();
        restartConfigurator();
        verifyDebugLevel();

        assertThat(configurator.getLevelsByLoggerName()).containsOnlyKeys(LOGGER_NAME).containsValue("DEBUG");

        configurator.resetLoggingLevel(LOGGER_NAME);
        verifyInfoLevel();
        restartConfigurator();
        verifyInfoLevel();
    }

    private static void restartConfigurator() {
        configurator.stop();
        startConfigurator();
    }

    private static void startConfigurator() {
        configurator = new PersistingLog4jLevelConfigurator(varStore, "prefix");
        configurator.start();
    }

    private static void verifyInfoLevel() throws IOException {
        verify("infoMsg");
    }

    private static void verifyDebugLevel() throws IOException {
        verify("debugMsg", "infoMsg");
    }

    private static void verifyTraceLevel() throws IOException {
        verify("traceMsg", "debugMsg", "infoMsg");
    }

    private static void verify(String... expected) throws IOException {
        var verificationId = verificationCounter++;
        var vidMarker = "VID=" + verificationId;
        logger.trace("traceMsg{}", vidMarker);
        logger.debug("debugMsg{}", vidMarker);
        logger.info("infoMsg{}", vidMarker);
        assertThat(Files.readAllLines(logFile).stream()
                        .filter(s -> s.startsWith(LOGGER_NAME))
                        .filter(s -> s.contains(vidMarker))
                        .map(s -> s.substring(LOGGER_NAME.length() + 1, s.length() - vidMarker.length())))
                .containsExactly(expected);
    }
}