package net.yudichev.jiotty.persistence.varstore;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

final class MultiUserFileVarStore extends BaseFileVarStore {

    public MultiUserFileVarStore(Path storeFile, @Nullable VarStoreEncryption encryption) {
        super(storeFile, encryption);
    }

    @Override
    public VarStore forUser(String userId) {
        Utils.validateUserId(userId);
        return new UserScopedVarStore(this, userId + '.');
    }
}
