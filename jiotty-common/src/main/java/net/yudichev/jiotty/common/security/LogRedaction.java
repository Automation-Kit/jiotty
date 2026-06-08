package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.geo.LatLonRectangle;
import org.apache.logging.log4j.util.StringBuilderFormattable;
import org.jspecify.annotations.Nullable;

/// Helpers for reducing secrets and PII to a short, non-reversible form before they reach a logger, MDC field, listener callback description, or exception
/// message.
///
/// The [StringBuilderFormattable]-returning variants are the primary implementations: they append directly to the target buffer, so the redacted value is
/// built once on the logging thread with no intermediate string. The plain [String] variants delegate to them, materialising the result only when a caller
/// genuinely needs a [String] (exception messages, MDC, return values).
public final class LogRedaction {
    private LogRedaction() {
    }

    /// Returns a short non-reversible prefix of `value` suitable for log output: the first 3 characters followed by an ellipsis. For values of 3 characters or
    /// fewer, returns just the ellipsis. Use this for auth tokens, API keys, passwords, push tokens, session cookies, emails, phone numbers, and similar.
    public static String redact(String value) {
        return materialise(redacted(value));
    }

    /// [#redact(String)] but with the action delegated to the logging thread.
    public static StringBuilderFormattable redacted(String value) {
        return redacted(value, 0, value.length());
    }

    /// [#redacted(String)] applied to the `[startPos, endPos)` region of `value`, appending the kept prefix straight from that region. Lets callers redact a
    /// slice of a larger string without first allocating a substring (e.g. the VIN part of a prefixed car id).
    public static StringBuilderFormattable redacted(String value, int startPos, int endPos) {
        return buffer -> {
            if (endPos - startPos <= 3) {
                buffer.append('…');
            } else {
                buffer.append(value, startPos, startPos + 3).append('…');
            }
        };
    }

    /// Returns a coarse, non-identifying rendering of `latLon` suitable for log output: each coordinate rounded to one decimal place (~11 km), prefixed
    /// with `~` to flag the loss of precision, e.g. `~{51.5,-0.1}`. This keeps a debuggable hint of the region while preventing a precise home/movement
    /// coordinate from reaching the logs. A `null` location renders as `null` (not PII, and it tells the reader which value was absent). For the strongest
    /// posture, mask the coordinate entirely instead of coarsening.
    public static String redact(@Nullable LatLon latLon) {
        return materialise(redacted(latLon));
    }

    /// [#redact(LatLon)] but with the action delegated to the logging thread.
    public static StringBuilderFormattable redacted(@Nullable LatLon latLon) {
        return buffer -> {
            if (latLon == null) {
                buffer.append("null");
            } else {
                appendCoarsePair(buffer.append("~{"), latLon.lat(), latLon.lon()).append('}');
            }
        };
    }

    /// Returns a coarse, non-identifying rendering of `rectangle` suitable for log output, e.g. `~[{51.5,-0.1}..{51.6,0.0}]`. Each bound is coarsened
    /// exactly as in [#redact(LatLon)]. A `null` rectangle renders as `null`.
    public static String redact(@Nullable LatLonRectangle rectangle) {
        return materialise(redacted(rectangle));
    }

    /// [#redact(LatLonRectangle)] but with the action delegated to the logging thread.
    public static StringBuilderFormattable redacted(@Nullable LatLonRectangle rectangle) {
        return buffer -> {
            if (rectangle == null) {
                buffer.append("null");
            } else {
                appendCoarsePair(buffer.append("~[{"), rectangle.minLat(), rectangle.minLon()).append("}..{");
                appendCoarsePair(buffer, rectangle.maxLat(), rectangle.maxLon()).append("}]");
            }
        };
    }

    private static String materialise(StringBuilderFormattable formattable) {
        // 32 comfortably holds every redaction this class produces (longest is the rectangle form ~26 chars), avoiding the JDK default's first resize.
        var buffer = new StringBuilder(32);
        formattable.formatTo(buffer);
        return buffer.toString();
    }

    private static StringBuilder appendCoarsePair(StringBuilder buffer, double lat, double lon) {
        return buffer.append(coarsen(lat)).append(',').append(coarsen(lon));
    }

    private static double coarsen(double coordinate) {
        return Math.round(coordinate * 10.0) / 10.0;
    }
}
