package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public interface VarStore {
    void saveValue(String key, Object value);

    void clearValue(String key);

    /// Deletes every value held in this store's current scope: on a user-scoped view obtained from [#forUser], all of that user's values.
    ///
    /// @throws IllegalStateException if called on an unscoped multi-user store — scope to a user via [#forUser] first
    void clearAll();

    /// Returns every entry held in this store's current scope, for data export (GDPR Art. 15): each key with its value, but with values that are encrypted at
    /// rest (i.e. secrets) [redacted][ExportedEntry#redacted] rather than decrypted. Keys are scope-relative (the user-scope prefix, if any, stripped). The raw
    /// stored form is read, so a secret that has been decrypted into an in-memory cache by an earlier [#readValueEncrypted] is still reported as redacted —
    /// secret values never leave the store through this method.
    ///
    /// @throws IllegalStateException if called on an unscoped multi-user store — scope to a user via [#forUser] first
    List<ExportedEntry> exportEntries();

    <T> Optional<T> readValue(TypeToken<T> type, String key);

    /// Persists `value` encrypted at rest. Callers must use [#readValueEncrypted] to read values written via this method.
    ///
    /// @throws IllegalStateException if the store was not configured with encryption
    void saveValueEncrypted(String key, Object value);

    /// Reads a value written by [#saveValueEncrypted]. The stored value must be an encryption envelope; a value stored under this key in any other form is a
    /// broken invariant and is rejected rather than returned as plaintext.
    ///
    /// @throws IllegalStateException if the store was not configured with encryption, or the stored value is not an encryption envelope
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

    /// One exported entry: the scope-relative [#key], whether its value was [#redacted] (because it is stored encrypted at rest, i.e. a secret), and the
    /// value's JSON in [#valueJson] when not redacted (`null` when redacted — the secret is never decrypted for export).
    record ExportedEntry(String key, boolean redacted, @Nullable String valueJson) {
        public ExportedEntry {
            checkNotNull(key, "key");
            checkArgument(redacted == (valueJson == null), "valueJson must be present iff not redacted");
        }
    }
}
