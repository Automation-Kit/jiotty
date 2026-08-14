package net.yudichev.jiotty.common.testutil;

import com.google.common.base.Throwables;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

/// A log appender that records log lines carrying personal data, so that [PiiLogGuardInstaller] can fail the run that produced one. It works from what the
/// loggers render, so a value class whose `toString`/`formatTo` leaks is caught the first time a test logs it.
///
/// @implNote a violation carries the message's format string, keeping the personal data out of the build output; identical violations collapse. It is
/// recorded for the installer to report later because these lines are mostly logged on executor threads, where throwing would reach the application's own
/// error handling and arrive as an alert or a hung future.
final class PiiLogGuard extends AbstractAppender {
    /// Every distinct violation this JVM has produced, so a leaking statement in a hot loop enqueues once and [#PENDING] stays bounded by the number of
    /// leaking statements rather than by the number of lines they log.
    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /// Violations awaiting a report. Filled on whichever thread logged the line — a test thread, an executor task, a Jetty worker — and drained on the JUnit
    /// thread that runs `afterEach`; the guard owns neither, so the two cannot be merged onto one.
    private static final Queue<String> PENDING = new ConcurrentLinkedQueue<>();
    private static final List<PiiPattern> PATTERNS = List.of(
            new PiiPattern("email address", Pattern.compile("[\\w.%+-]+@[\\w-]+\\.[A-Za-z]{2,}")),
            // A VIN is 17 characters from an alphabet that excludes I, O and Q, with a digit and several letters. The lookaheads pin that length and demand
            // a digit and two letters, which is what a 17-letter word and the digits-then-`E` tail of `4.1544036666607553E-4` each fail.
            new PiiPattern("VIN", Pattern.compile(
                    "\\b(?=[A-HJ-NPR-Z0-9]{17}\\b)(?=[0-9]*[A-HJ-NPR-Z][0-9]*[A-HJ-NPR-Z])[A-HJ-NPR-Z0-9]*[0-9][A-HJ-NPR-Z0-9]*")),
            // Four decimal places locate a building, and every coordinate this codebase renders carries one of these labels. Two bare decimals side by side
            // are far more often a pair of prices, which is why the label is part of the match.
            new PiiPattern("precise coordinate", Pattern.compile("(?i)\\b(?:lat|latitude|lon|lng|longitude)\\W{0,3}-?\\d{1,3}\\.\\d{4,}")));

    PiiLogGuard() {
        super("piiLogGuard", null, null, true, Property.EMPTY_ARRAY);
    }

    /// Returns the kind of personal data `text` carries, or empty when it carries none.
    static Optional<String> scan(CharSequence text) {
        for (PiiPattern piiPattern : PATTERNS) {
            if (piiPattern.pattern().matcher(text).find()) {
                return Optional.of(piiPattern.category());
            }
        }
        return Optional.empty();
    }

    /// Returns the violations recorded since the previous call, in the order they arrived, and forgets them. Each is returned exactly once in a JVM: the
    /// queue gives up one element per [Queue#poll()], so a line logged while a drain is in flight joins the next drain.
    static Set<String> drainViolations() {
        var drained = new LinkedHashSet<String>();
        for (String violation = PENDING.poll(); violation != null; violation = PENDING.poll()) {
            drained.add(violation);
        }
        return drained;
    }

    @Override
    public void append(LogEvent event) {
        record(event, event.getMessage().getFormattedMessage());
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            Throwables.getCausalChain(thrown).forEach(cause -> record(event, String.valueOf(cause.getMessage())));
        }
    }

    // getFormat() is deprecated in favour of getFormattedMessage(), which is precisely the rendering that carries the personal data; the format string is
    // what identifies the statement without reproducing it.
    @SuppressWarnings("deprecation")
    private static void record(LogEvent event, CharSequence text) {
        scan(text).ifPresent(category -> {
            String description =
                    category + " reached a log line from " + event.getLoggerName() + ", whose message is \"" + event.getMessage().getFormat() + '"';
            if (SEEN.add(description)) {
                PENDING.add(description);
            }
        });
    }

    private record PiiPattern(String category, Pattern pattern) {}
}
