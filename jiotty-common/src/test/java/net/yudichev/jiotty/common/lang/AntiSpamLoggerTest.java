package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AntiSpamLoggerTest {
    @Mock
    private Logger logger;
    @Captor
    private ArgumentCaptor<String> messageCaptor;
    @Captor
    private ArgumentCaptor<Object[]> paramsCaptor;

    // A fresh instance per test gives an isolated suppression map, so dedup behaviour is deterministic regardless of method order or reused message templates.
    private AntiSpamLogger antiSpamLogger;

    @BeforeEach
    void setUp() {
        antiSpamLogger = new AntiSpamLogger();
    }

    @Test
    void sameMessageWithinWindow_loggedOnce() {
        var error = new RuntimeException("boom");

        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.INFO, "recurring failure", error);
        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.INFO, "recurring failure", error);

        verify(logger, times(1)).log(eq(Level.INFO), eq("recurring failure"), any(Object[].class));
    }

    @Test
    void windowElapsed_logsAgain() {
        var error = new RuntimeException("boom");

        // A zero window never suppresses, so the same message logs every time — exercising the "window has elapsed" path.
        antiSpamLogger.logThrottled(logger, Duration.ZERO, Level.INFO, "recurring failure", error);
        antiSpamLogger.logThrottled(logger, Duration.ZERO, Level.INFO, "recurring failure", error);

        verify(logger, times(2)).log(eq(Level.INFO), eq("recurring failure"), any(Object[].class));
    }

    @Test
    void distinctMessagesAreSuppressedIndependently() {
        var error = new RuntimeException("boom");

        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.INFO, "failure A", error);
        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.INFO, "failure B", error);

        verify(logger, times(2)).log(eq(Level.INFO), messageCaptor.capture(), any(Object[].class));
        assertThat(messageCaptor.getAllValues()).containsExactly("failure A", "failure B");
    }

    @Test
    void forwardsTemplateAndParamsIncludingTrailingThrowable() {
        var error = new RuntimeException("boom");

        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.WARN, "decode failed for format {}", 1, error);

        verify(logger).log(eq(Level.WARN), eq("decode failed for format {}"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsExactly(1, error);
    }

    @Test
    void forwardsTemplateAndParamsWithoutThrowable() {
        antiSpamLogger.logThrottled(logger, Duration.ofHours(1), Level.INFO, "count={}", 7);

        verify(logger).log(eq(Level.INFO), eq("count={}"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue()).containsExactly(7);
    }

    @Test
    void staticLog_logsThroughTheSharedInstance() {
        // The public entry point delegates to the process-wide shared instance; a message unique to this test avoids cross-test pollution of that shared map.
        AntiSpamLogger.log(logger, Duration.ofHours(1), Level.INFO, "static delegation probe", 1);

        verify(logger).log(eq(Level.INFO), eq("static delegation probe"), any(Object[].class));
    }
}
