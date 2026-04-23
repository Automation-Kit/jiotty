package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.Optional;

import static net.yudichev.jiotty.persistence.varstore.Bindings.SingleUser;
import static net.yudichev.jiotty.persistence.varstore.Bindings.ThePath;

public final class FileVarStore implements VarStore {
    private final BaseFileVarStore delegate;

    @Inject
    public FileVarStore(@ThePath Path path, @SingleUser boolean singleUser, Optional<VarStoreEncryption> encryption) {
        delegate = singleUser
                   ? new SingleUserFileVarStore(path, encryption.orElse(null))
                   : new MultiUserFileVarStore(path, encryption.orElse(null));
    }

    @Override
    public void saveValue(String key, Object value) {
        delegate.saveValue(key, value);
    }

    @Override
    public void saveValueEncrypted(String key, Object value) {
        delegate.saveValueEncrypted(key, value);
    }

    @Override
    public void clearValue(String key) {
        delegate.clearValue(key);
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return delegate.readValue(type, key);
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        return delegate.readValueEncrypted(type, key);
    }

    @Override
    public VarStore forUser(String userId) {
        return delegate.forUser(userId);
    }
}
