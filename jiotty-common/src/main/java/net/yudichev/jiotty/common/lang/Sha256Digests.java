package net.yudichev.jiotty.common.lang;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/// SHA-256 for deriving stable ids from content, in the form callers of this library want it: one digest per thread, and a URL-safe rendering of its value.
public final class Sha256Digests {
    /// [MessageDigest] accumulates into itself, so a shared one would interleave the input of any two threads digesting at once.
    private static final ThreadLocal<MessageDigest> SHA_256 = ThreadLocal.withInitial(Sha256Digests::newSha256);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private Sha256Digests() {
    }

    /// @return this thread's digest, holding nothing and ready to accumulate
    public static MessageDigest resetDigest() {
        MessageDigest digest = SHA_256.get();
        digest.reset();
        return digest;
    }

    /// Renders what `digest` has accumulated, leaving it reset for its next use.
    ///
    /// @return the digest's 32 bytes in URL-safe base64 without padding, so the value is safe in a key, a path or a query string
    public static String encodeUrl(MessageDigest digest) {
        return URL_ENCODER.encodeToString(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }
}
