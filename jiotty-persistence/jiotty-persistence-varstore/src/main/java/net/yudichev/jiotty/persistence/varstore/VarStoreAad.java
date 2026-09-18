package net.yudichev.jiotty.persistence.varstore;

import net.yudichev.jiotty.common.security.EnvelopeEncryption;

/// The associated data binding a VarStore envelope to the row it belongs to, so a value lifted into another user's row, or another key, fails to decrypt.
///
/// The wire format is part of every envelope already written: changing the tag, the separator or the field order makes every stored value undecryptable.
final class VarStoreAad {
    private static final String AAD_TAG = "jiotty-varstore-v1";

    private VarStoreAad() {
    }

    /// Builds the associated data for [EnvelopeEncryption#encrypt] and [EnvelopeEncryption#decrypt].
    ///
    /// @param userId the owning user, empty for a file-backed store, which holds one user's values per file
    /// @param key    the VarStore key the value is stored under
    public static String of(String userId, String key) {
        return AAD_TAG + '|' + userId + '|' + key;
    }
}
