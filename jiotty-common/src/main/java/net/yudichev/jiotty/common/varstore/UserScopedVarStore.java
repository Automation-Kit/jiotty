package net.yudichev.jiotty.common.varstore;

import com.google.common.reflect.TypeToken;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

final class UserScopedVarStore implements VarStore {
    private final VarStore delegate;
    private final String keyPrefix;

    UserScopedVarStore(VarStore delegate, String keyPrefix) {
        this.delegate = checkNotNull(delegate, "delegate");
        this.keyPrefix = checkNotNull(keyPrefix, "keyPrefix");
    }

    @Override
    public void saveValue(String key, Object value) {
        delegate.saveValue(keyPrefix + key, value);
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return delegate.readValue(type, keyPrefix + key);
    }

    @Override
    public VarStore forUser(String userId) {
        throw new IllegalStateException("VarStore is already scoped to a user");
    }
}
