package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;

import java.util.Optional;

public interface VarStore {
    void saveValue(String key, Object value);

    void clearValue(String key);

    <T> Optional<T> readValue(TypeToken<T> type, String key);

    /// Persists `value` encrypted at rest. Callers must use [#readValueEncrypted] to read values written via this method.
    ///
    /// @throws IllegalStateException if the store was not configured with encryption
    void saveValueEncrypted(String key, Object value);

    /// Reads a value written by [#saveValueEncrypted].
    ///
    /// Tolerates legacy plaintext values for the same key: if the stored value is not encrypted., it is decoded as plain JSON and the row is immediately
    /// re-encrypted in place.
    ///
    /// @throws IllegalStateException if the store was not configured with encryption
    <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key);

    /// Returns a user-scoped view of the store.
    ///
    /// The returned store may not be re-scoped again via this method.
    VarStore forUser(String userId);

    default <T> Optional<T> readValue(Class<T> type, String key) {
        return readValue(TypeToken.of(type), key);
    }

    default <T> Optional<T> readValueEncrypted(Class<T> type, String key) {
        return readValueEncrypted(TypeToken.of(type), key);
    }
}
