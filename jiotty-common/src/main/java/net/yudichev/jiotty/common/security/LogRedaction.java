package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.geo.LatLonRectangle;
import net.yudichev.jiotty.common.lang.Append;
import org.apache.logging.log4j.util.StringBuilderFormattable;
import org.jspecify.annotations.Nullable;

/// Helpers for reducing secrets and PII to a short, non-reversible form before they reach a logger, MDC field, listener callback description, or exception
/// message.
///
/// The `appendRedacted` variants are the primary implementations: they append directly to the target [Appendable], so the redacted value is built once with
/// no intermediate string — use them when the caller already holds a buffer. The [StringBuilderFormattable]-returning `redacted` variants are designed to be
/// used as logging arguments: they defer the redaction to the logging thread, writing straight into the log line's buffer when the line actually renders.
/// The plain [String] variants materialise the result only when a caller genuinely needs a [String] (exception messages, MDC, return values).
public final class LogRedaction {
    private LogRedaction() {
    }

    /// Returns a short non-reversible prefix of `value` suitable for log output: the first 3 characters followed by an ellipsis. For values of 3 characters or
    /// fewer, returns just the ellipsis. Use this for auth tokens, API keys, passwords, push tokens, session cookies, emails, phone numbers, and similar.
    public static String redact(String value) {
        return materialise(redacted(value));
    }

    /// [#redact(String)] as a deferred logging argument: the redaction is delegated to the logging thread.
    public static StringBuilderFormattable redacted(String value) {
        return buffer -> appendRedacted(buffer, value);
    }

    /// [#redact(String)] but appending straight to `appendable`, with no intermediate string.
    public static void appendRedacted(Appendable appendable, String value) {
        appendRedacted(appendable, value, 0, value.length());
    }

    /// [#redacted(String)] applied to the `[startPos, endPos)` region of `value`, as a deferred logging argument.
    public static StringBuilderFormattable redacted(String value, int startPos, int endPos) {
        return buffer -> appendRedacted(buffer, value, startPos, endPos);
    }

    /// [#appendRedacted(Appendable, String)] applied to the `[startPos, endPos)` region of `value`, appending the kept prefix straight from that region. Lets
    /// callers redact a slice of a larger string without first allocating a substring (e.g. the VIN part of a prefixed car id).
    public static void appendRedacted(Appendable appendable, String value, int startPos, int endPos) {
        if (endPos - startPos > 3) {
            Append.to(appendable, value, startPos, startPos + 3);
        }
        Append.to(appendable, '…');
    }

    /// Returns a coarse, non-identifying rendering of `latLon` suitable for log output: each coordinate rounded to one decimal place (~11 km), prefixed
    /// with `~` to flag the loss of precision, e.g. `~{51.5,-0.1}`. This keeps a debuggable hint of the region while preventing a precise home/movement
    /// coordinate from reaching the logs. A `null` location renders as `null` (not PII, and it tells the reader which value was absent). For the strongest
    /// posture, mask the coordinate entirely instead of coarsening.
    public static String redact(@Nullable LatLon latLon) {
        return materialise(redacted(latLon));
    }

    /// [#redact(LatLon)] as a deferred logging argument: the redaction is delegated to the logging thread.
    public static StringBuilderFormattable redacted(@Nullable LatLon latLon) {
        return buffer -> appendRedacted(buffer, latLon);
    }

    /// [#redact(LatLon)] but appending straight to `appendable`, with no intermediate string.
    public static void appendRedacted(Appendable appendable, @Nullable LatLon latLon) {
        if (latLon == null) {
            Append.to(appendable, "null");
        } else {
            Append.to(appendable, "~{");
            appendCoarsePair(appendable, latLon.lat(), latLon.lon());
            Append.to(appendable, '}');
        }
    }

    /// Returns a coarse, non-identifying rendering of `rectangle` suitable for log output, e.g. `~[{51.5,-0.1}..{51.6,0.0}]`. Each bound is coarsened
    /// exactly as in [#redact(LatLon)]. A `null` rectangle renders as `null`.
    public static String redact(@Nullable LatLonRectangle rectangle) {
        return materialise(redacted(rectangle));
    }

    /// [#redact(LatLonRectangle)] as a deferred logging argument: the redaction is delegated to the logging thread.
    public static StringBuilderFormattable redacted(@Nullable LatLonRectangle rectangle) {
        return buffer -> appendRedacted(buffer, rectangle);
    }

    /// [#redact(LatLonRectangle)] but appending straight to `appendable`, with no intermediate string.
    public static void appendRedacted(Appendable appendable, @Nullable LatLonRectangle rectangle) {
        if (rectangle == null) {
            Append.to(appendable, "null");
        } else {
            Append.to(appendable, "~[{");
            appendCoarsePair(appendable, rectangle.minLat(), rectangle.minLon());
            Append.to(appendable, "}..{");
            appendCoarsePair(appendable, rectangle.maxLat(), rectangle.maxLon());
            Append.to(appendable, "}]");
        }
    }

    private static String materialise(StringBuilderFormattable formattable) {
        // 32 comfortably holds every redaction this class produces (longest is the rectangle form ~26 chars), avoiding the JDK default's first resize.
        var buffer = new StringBuilder(32);
        formattable.formatTo(buffer);
        return buffer.toString();
    }

    private static void appendCoarsePair(Appendable appendable, double lat, double lon) {
        Append.to(appendable, coarsen(lat));
        Append.to(appendable, ',');
        Append.to(appendable, coarsen(lon));
    }

    private static double coarsen(double coordinate) {
        return Math.round(coordinate * 10.0) / 10.0;
    }
}
