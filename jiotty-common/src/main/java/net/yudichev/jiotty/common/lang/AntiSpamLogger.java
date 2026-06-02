package net.yudichev.jiotty.common.lang;

import com.google.common.annotations.VisibleForTesting;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkNotNull;

/// Static helper that drops repeat emissions of the same log message within a time window — for noisy, high-frequency failure paths where one line per outage
/// window is enough. Suppression is keyed by the `message` template (the pattern, not the formatted result), so differing parameters do not bypass it, and the
/// suppression state is process-wide and shared across all callers. Thread-safe.
///
/// Timing reads the system clock directly ([Instant#now]); it is for diagnostic anti-spam, not for logic that needs an injectable clock.
public final class AntiSpamLogger {
    private static final AntiSpamLogger SHARED = new AntiSpamLogger();

    private final Map<String, Instant> lastLoggedByMessage = new ConcurrentHashMap<>();

    @VisibleForTesting
    AntiSpamLogger() {
    }

    /// Logs `message` (formatted with `params`, log4j `{}` style, with a trailing [Throwable] in `params` rendered as a stack trace) at `level` through
    /// `logger` — unless the same `message` was logged less than `minInterval` ago, in which case the call is silently dropped. Suppression state is
    /// process-wide.
    public static void log(Logger logger, Duration minInterval, Level level, String message, Object... params) {
        SHARED.logThrottled(logger, minInterval, level, message, params);
    }

    /// [#log] backing logic against this instance's own suppression map. Package-private so a test can exercise dedup on an isolated instance without
    /// process-wide state bleeding between test methods.
    @VisibleForTesting
    void logThrottled(Logger logger, Duration minInterval, Level level, String message, Object... params) {
        checkNotNull(logger, "logger");
        checkNotNull(minInterval, "minInterval");
        checkNotNull(level, "level");
        checkNotNull(message, "message");
        Instant now = Instant.now();
        // compute atomically records `now` and returns it when this message is due (never logged, or the window has elapsed), or keeps and returns the previous
        //  timestamp while still within the window — so the identity check below is a race-free "did this call just claim the slot?".
        Instant stamp = lastLoggedByMessage.compute(message, (_, last) ->
                last == null || !now.isBefore(last.plus(minInterval)) ? now : last);
        if (stamp == now) {
            logger.log(level, message, params);
        }
    }
}
