package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.security.EnvelopeEncryption;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static net.yudichev.jiotty.persistence.varstore.Bindings.SingleUser;
import static net.yudichev.jiotty.persistence.varstore.Bindings.ThePath;

public final class FileVarStore implements VarStore {
    private final BaseFileVarStore delegate;
    private final boolean singleUser;

    @Inject
    public FileVarStore(@ThePath Path path, @SingleUser boolean singleUser, Optional<EnvelopeEncryption> encryption) {
        this.singleUser = singleUser;
        delegate = singleUser
                   ? new SingleUserFileVarStore(path, encryption.orElse(null))
                   : new MultiUserFileVarStore(path, encryption.orElse(null));
    }

    @Override
    public void saveValue(String key, Object value) {
        delegate.saveValue(unscopedKey(key), value);
    }

    @Override
    public void saveValueEncrypted(String key, Object value) {
        delegate.saveValueEncrypted(unscopedKey(key), value);
    }

    @Override
    public void clearValue(String key) {
        delegate.clearValue(unscopedKey(key));
    }

    @Override
    public void clearAll() {
        delegate.clearAll();
    }

    @Override
    public List<ExportedEntry> exportEntries() {
        return delegate.exportEntries();
    }

    @Override
    public Set<String> keysStartingWith(String prefix) {
        return delegate.keysStartingWith(prefix);
    }

    @Override
    public <T> Optional<T> readValue(TypeToken<T> type, String key) {
        return delegate.readValue(type, unscopedKey(key));
    }

    @Override
    public <T> Optional<T> readValueEncrypted(TypeToken<T> type, String key) {
        return delegate.readValueEncrypted(type, unscopedKey(key));
    }

    @Override
    public VarStore forUser(String userId) {
        return delegate.forUser(userId);
    }

    /// Returns `key` once it is checked to lie outside every user's keys. The multi-user file stores a user's key as `<userId>.<key>`, so an unscoped key
    /// holding a dot would be one of some user's keys.
    private String unscopedKey(String key) {
        checkArgument(singleUser || key.indexOf('.') < 0, "a key of the unscoped multi-user store must not contain a dot: %s", key);
        return key;
    }
}
