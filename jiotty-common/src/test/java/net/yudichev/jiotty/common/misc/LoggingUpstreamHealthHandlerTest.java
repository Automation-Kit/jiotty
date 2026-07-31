package net.yudichev.jiotty.common.misc;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class LoggingUpstreamHealthHandlerTest {

    @Mock
    private Logger logger;

    @Test
    void logsFailureOnceWhileStillFailing() {
        var handler = new LoggingUpstreamHealthHandler("Octopus API", Level.INFO, logger);

        handler.onFailure("Retries overran", new RuntimeException("boom 1"));
        handler.onFailure("Retries overran again", new RuntimeException("boom 2"));

        verify(logger, times(1)).log(eq(Level.INFO), anyString(), anyString(), anyString(), any(Throwable.class));
    }

    @Test
    void logsRecoveryOnceAfterFailure() {
        var handler = new LoggingUpstreamHealthHandler("Octopus API", Level.INFO, logger);

        handler.onFailure("Retries overran", new RuntimeException("boom"));
        handler.onSuccess();
        handler.onSuccess();

        verify(logger, times(1)).log(Level.INFO, "{} recovered", "Octopus API");
    }

    @Test
    void successWithoutPriorFailureLogsNothing() {
        var handler = new LoggingUpstreamHealthHandler("Octopus API", Level.INFO, logger);

        handler.onSuccess();

        verifyNoInteractions(logger);
    }

    @Test
    void reLogsFailureAfterRecovery() {
        var handler = new LoggingUpstreamHealthHandler("Octopus API", Level.INFO, logger);

        handler.onFailure("Retries overran", new RuntimeException("boom 1"));
        handler.onSuccess();
        handler.onFailure("Retries overran", new RuntimeException("boom 2"));

        verify(logger, times(2)).log(eq(Level.INFO), anyString(), anyString(), anyString(), any(Throwable.class));
    }

    @Test
    void logsAtTheConfiguredLevel() {
        // A host whose only operator channel is level-gated (e.g. a WARN-routed mail appender) raises the level; the transitions must then log at it.
        var handler = new LoggingUpstreamHealthHandler("Octopus API", Level.WARN, logger);

        handler.onFailure("Retries overran", new RuntimeException("boom"));
        handler.onSuccess();

        verify(logger).log(eq(Level.WARN), anyString(), anyString(), anyString(), any(Throwable.class));
        verify(logger).log(Level.WARN, "{} recovered", "Octopus API");
    }
}
