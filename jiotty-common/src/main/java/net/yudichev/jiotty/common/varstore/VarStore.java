package net.yudichev.jiotty.common.varstore;

import com.google.common.reflect.TypeToken;

import java.util.Optional;

public interface VarStore {
    void saveValue(String key, Object value);

    <T> Optional<T> readValue(TypeToken<T> type, String key);

    /// Returns a user-scoped view of the store.
    ///
    /// The returned store may not be re-scoped again via this method.
    VarStore forUser(String userId);

    default <T> Optional<T> readValue(Class<T> type, String key) {
        return readValue(TypeToken.of(type), key);
    }
}
