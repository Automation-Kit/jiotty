package net.yudichev.jiotty.persistence.varstore;

import java.nio.file.Path;

final class MultiUserFileVarStore extends BaseFileVarStore {

    public MultiUserFileVarStore(Path storeFile) {
        super(storeFile);
    }

    @Override
    public VarStore forUser(String userId) {
        Utils.validateUserId(userId);
        return new UserScopedVarStore(this, userId + '.');
    }
}
