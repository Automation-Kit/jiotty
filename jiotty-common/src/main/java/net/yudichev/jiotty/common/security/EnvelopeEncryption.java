package net.yudichev.jiotty.common.security;

/// AES-256-GCM envelope encryption for values held at rest, returning a {@value #ENVELOPE_PREFIX}-prefixed envelope carrying the key id and nonce alongside
/// the ciphertext.
///
/// The caller owns the associated data binding each ciphertext to its context, and must reproduce it exactly to decrypt.
public interface EnvelopeEncryption {
    String ENVELOPE_PREFIX = "ENC1$";

    /// Encrypts `plaintext` under `associatedData` and returns a {@value #ENVELOPE_PREFIX}-prefixed envelope.
    String encrypt(String associatedData, String plaintext);

    /// Decrypts an envelope produced by [#encrypt] under the same `associatedData`.
    ///
    /// @throws IllegalArgumentException if `envelope` does not start with [#ENVELOPE_PREFIX]
    /// @throws IllegalStateException    if decryption fails (wrong key, tampered ciphertext, mismatched associated data)
    String decrypt(String associatedData, String envelope);

    static boolean isEnvelope(String stored) {
        return stored != null && stored.startsWith(ENVELOPE_PREFIX);
    }
}
