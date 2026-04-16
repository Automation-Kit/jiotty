package net.yudichev.jiotty.common.security;

/// Helpers for reducing secrets and PII to a short, non-reversible form before they reach a logger, MDC field, listener callback description, or exception
/// message.
public final class LogRedaction {
    private LogRedaction() {
    }

    /// Returns a short non-reversible prefix of `value` suitable for log output: the first 3 characters followed by an ellipsis. For values of 3 characters or
    /// fewer, returns just the ellipsis. Use this for auth tokens, API keys, passwords, push tokens, session cookies, emails, phone numbers, and similar.
    public static String redact(String value) {
        if (value.length() <= 3) {
            return "…";
        }
        return value.substring(0, 3) + "…";
    }
}
