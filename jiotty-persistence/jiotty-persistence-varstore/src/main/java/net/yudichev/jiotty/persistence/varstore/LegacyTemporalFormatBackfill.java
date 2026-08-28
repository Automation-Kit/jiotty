package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// TEMPORARY — delete once both deployments start without logging a rewrite.
///
/// Rewrites a row whose stored JSON predates [VarStoreJson]'s ISO-8601 temporal form, hanging off the read path because that is where a stored value's type
/// is known.
final class LegacyTemporalFormatBackfill {
    private static final Logger logger = LogManager.getLogger(LegacyTemporalFormatBackfill.class);

    private LegacyTemporalFormatBackfill() {
    }

    /// Both sides are parsed before comparison so that whitespace and Jackson's numeric node types cancel out: a stored `42` and a `long` holding 42 build
    /// different node types, and comparing those directly calls every numeric row stale and rewrites it on every read.
    public static boolean storedFormIsStale(ObjectMapper mapper, String storedJson, Object value) {
        return getAsUnchecked(() -> !mapper.readTree(mapper.writeValueAsString(value)).equals(mapper.readTree(storedJson)));
    }

    /// Logs the user and the key; a var-store value is the subject's own personal data.
    public static void logRewrite(String userId, String key) {
        logger.info("[{}] Rewriting '{}' from the legacy temporal format", userId, key);
    }

    /// For a single-user store, whose line names the key alone.
    public static void logRewrite(String key) {
        logger.info("Rewriting '{}' from the legacy temporal format", key);
    }

    /// Logs a rewrite the store could not complete, leaving the row in its stored form for the next read to retry.
    public static void logRewriteFailure(String key, Exception e) {
        logger.info("Failed to rewrite '{}' from the legacy temporal format", key, e);
    }
}
