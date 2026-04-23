package net.yudichev.jiotty.common.keystore;

import javax.crypto.SecretKey;

public interface KeyStoreAccess {
    String getEntry(String alias);

    /// Returns the [SecretKey] stored under `alias` as a PKCS#12 `SecretKeyEntry`.
    ///
    /// Unlike [#getEntry(String)], this method does not round-trip the key bytes through UTF-8
    /// — use it for raw symmetric keys (e.g. AES master keys) whose bytes are not a valid UTF-8
    /// string.
    default SecretKey getSecretKey(String alias) {
        throw new UnsupportedOperationException("getSecretKey not implemented by " + getClass().getName());
    }
}
