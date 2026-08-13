package net.yudichev.jiotty.common.time;

import com.google.common.base.Preconditions;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Utility for human-friendly duration formatting and flexible parsing.
public final class FriendlyDurationFormat {
    private static final Pattern DAYS_TOKEN = Pattern.compile("(\\d+)\\s*d");
    private static final Pattern CLOCK_TOKEN = Pattern.compile("(\\d+):(\\d{1,2})(?::(\\d{1,2}))?");
    private static final Pattern H_TOKEN = Pattern.compile("(\\d+)\\s*h");
    private static final Pattern M_TOKEN = Pattern.compile("(\\d+)\\s*m");
    private static final Pattern S_TOKEN = Pattern.compile("(\\d+)\\s*s");
    private static final char[] FORMATTING_SUFFIXES = {'d', 'h', 'm', 's'};

    private FriendlyDurationFormat() {
    }

    /// Parse a human-friendly duration string. Accepted formats include: - HH:MM or HH:MM:SS (e.g., 02:30 or 12:05:10) - Nd HH:MM[:SS] (e.g., 1d 02:30 or 2d
    /// 00:00:15) - Unit tokens: "2h 30m", "90m", "3600s", "1d 2h", etc. - ISO-8601 like PT2H30M (case-insensitive)
    ///
    /// @throws NullPointerException     if `raw` is null
    /// @throws IllegalArgumentException for invalid input (including blank input)
    public static Duration parseHuman(String raw) {
        Preconditions.checkNotNull(raw, "raw");
        String s = raw.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Invalid duration: empty input");
        }
        // Try ISO-8601 first (case-insensitive P...)
        if (s.length() >= 2 && (s.charAt(0) == 'P' || s.charAt(0) == 'p')) {
            try {
                return Duration.parse(s.toUpperCase(Locale.ROOT));
            } catch (RuntimeException ignored) {
                // fall through to flexible parsing
            }
        }

        long days = 0, hours = 0, minutes = 0, seconds = 0;
        // Zero is a legitimate duration ("0s"), so an input is invalid exactly when it contains no recognised token.
        boolean anyTokenMatched = false;

        // Extract days token if present (e.g., "2d")
        Matcher matcher = DAYS_TOKEN.matcher(s.toLowerCase(Locale.ROOT));
        if (matcher.find()) {
            days = Long.parseLong(matcher.group(1));
            anyTokenMatched = true;
            // remove the matched days token for simpler further parsing
            s = new StringBuilder(s.length()).append(s).delete(matcher.start(), matcher.end()).toString();
        }

        // Try clock token (HH:MM[:SS])
        matcher = CLOCK_TOKEN.matcher(s.trim());
        if (matcher.find()) {
            hours = Long.parseLong(matcher.group(1));
            minutes = Long.parseLong(matcher.group(2));
            if (matcher.group(3) != null) {
                seconds = Long.parseLong(matcher.group(3));
            }
            // Validate ranges
            if (minutes >= 60 || seconds >= 60) {
                throw new IllegalArgumentException("Invalid duration: minutes and seconds must be < 60");
            }
        } else {
            // Fallback to unit tokens h/m/s in any order (e.g., "2h 30m 5s" or "90m")
            String lowerCase = s.toLowerCase(Locale.ROOT);
            Matcher mh = H_TOKEN.matcher(lowerCase);
            if (mh.find()) {
                hours = Long.parseLong(mh.group(1));
                anyTokenMatched = true;
            }
            Matcher mm = M_TOKEN.matcher(lowerCase);
            if (mm.find()) {
                minutes = Long.parseLong(mm.group(1));
                anyTokenMatched = true;
            }
            Matcher ms = S_TOKEN.matcher(lowerCase);
            if (ms.find()) {
                seconds = Long.parseLong(ms.group(1));
                anyTokenMatched = true;
            }

            // If nothing matched at all, it's an error
            if (!anyTokenMatched) {
                throw new IllegalArgumentException("Invalid duration: '" + raw + "'");
            }
        }

        // Disallow negative values
        if (days < 0 || hours < 0 || minutes < 0 || seconds < 0) {
            throw new IllegalArgumentException("Invalid duration: negative values are not allowed");
        }

        return Duration.ofDays(days)
                       .plusHours(hours)
                       .plusMinutes(minutes)
                       .plusSeconds(seconds);
    }

    /// Formats duration to human-friendly compact form like "2h 30m", "1d 2h 5s", "0s" for zero.
    ///
    /// @throws NullPointerException     if `duration` is null
    /// @throws IllegalArgumentException if `duration` is negative
    public static String formatHuman(Duration duration) {
        Preconditions.checkNotNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("Invalid duration: negative values are not allowed");
        }
        long days = duration.toDaysPart();
        int hours = duration.toHoursPart();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();

        // If everything is zero, show 0s
        if (days == 0 && hours == 0 && minutes == 0 && seconds == 0) {
            return "0s";
        }

        // Build array of units in order
        long[] values = {days, hours, minutes, seconds};

        // Find first non-zero (to skip leading zero units) and last non-zero (to trim trailing zeroes)
        int first = 0;
        while (first < values.length && values[first] == 0) {
            first++;
        }
        int last = values.length - 1;
        while (last >= 0 && values[last] == 0) {
            last--;
        }

        // Assumption: up to 4 tokens (d, h, m, s), each roughly up to ~7 characters incl. space and suffix.
        var sb = new StringBuilder(4 * 8);
        for (int i = first; i <= last; i++) {
            // Skip zero components in the middle to keep it concise
            if (values[i] != 0) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(values[i]).append(FORMATTING_SUFFIXES[i]);
            }
        }
        return sb.toString();
    }
}
