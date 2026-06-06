package net.yudichev.jiotty.persistence.varstore;

/// AES-256-GCM envelope encryption for VarStore values.
///
/// Envelope layout (base64 after the `ENC1$` sigil): `magic(3B="ENC") | ver(1B=0x01) | keyId(1B) | nonceLen(1B=12) | nonce(12B) | ciphertext||tag`.
///
/// AAD is `"jiotty-varstore-v1|" + userId + "|" + key`, binding the ciphertext to its row.
public interface VarStoreEncryption {
    String ENVELOPE_PREFIX = "ENC1$";

    /// Encrypts `plaintextJson` under `(userId, key)` AAD and returns an `ENC1$…` envelope.
    String encrypt(String userId, String key, String plaintextJson);

    /// Decrypts an `ENC1$…` envelope produced by [#encrypt] under the same `(userId, key)`.
    ///
    /// @throws IllegalArgumentException if `envelope` does not start with [#ENVELOPE_PREFIX]
    /// @throws IllegalStateException    if decryption fails (wrong key, tampered ciphertext, mismatched AAD)
    String decrypt(String userId, String key, String envelope);

    static boolean isEnvelope(String stored) {
        return stored != null && stored.startsWith(ENVELOPE_PREFIX);
    }
}
