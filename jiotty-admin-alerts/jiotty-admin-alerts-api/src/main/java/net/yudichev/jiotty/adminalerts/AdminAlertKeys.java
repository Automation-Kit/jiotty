package net.yudichev.jiotty.adminalerts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Sha256Digests.encodeUrl;
import static net.yudichev.jiotty.common.lang.Sha256Digests.resetDigest;

/// Canonical key derivation for [AdminAlertData]. The key is a stable function of the alert's identity-bearing accessors ([AdminAlertData#title()],
/// [AdminAlertData#severity()], [AdminAlertData#labels()]) — the same inputs always produce the same key, regardless of label-map iteration order, so two
/// raises with the same identity coalesce onto the same alert bundle.
public final class AdminAlertKeys {
    private static final String AUTO_PREFIX = "auto:";

    private AdminAlertKeys() {
    }

    public static String derive(String title, AdminAlertSeverity severity, Map<String, String> labels) {
        checkNotNull(title, "title");
        checkNotNull(severity, "severity");
        checkNotNull(labels, "labels");

        MessageDigest digest = resetDigest();
        updateLengthPrefixedUtf8(digest, title);
        updateLengthPrefixedUtf8(digest, severity.name());
        var sorted = new TreeMap<>(labels);
        updateInt(digest, sorted.size());
        sorted.forEach((k, v) -> {
            updateLengthPrefixedUtf8(digest, k);
            updateLengthPrefixedUtf8(digest, v);
        });
        return AUTO_PREFIX + encodeUrl(digest);
    }

    private static void updateLengthPrefixedUtf8(MessageDigest digest, String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, utf8.length);
        digest.update(utf8);
    }

    private static void updateInt(MessageDigest digest, int v) {
        digest.update((byte) (v >>> 24));
        digest.update((byte) (v >>> 16));
        digest.update((byte) (v >>> 8));
        digest.update((byte) v);
    }
}
