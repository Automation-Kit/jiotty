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
    public FileVarStore(@ThePath Path path, @SingleUser boolean singleUser) {
        delegate = singleUser ? new SingleUserFileVarStore(path) : new MultiUserFileVarStore(path);
    }

    @Override
    public void saveValue(String key, Object value) {
        delegate.saveValue(key, value);
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return delegate.readValue(type, key);
    }

    @Override
    public VarStore forUser(String userId) {
        return delegate.forUser(userId);
    }
}
