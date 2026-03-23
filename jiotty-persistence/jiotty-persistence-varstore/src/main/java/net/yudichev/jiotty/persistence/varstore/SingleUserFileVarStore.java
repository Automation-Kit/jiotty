package net.yudichev.jiotty.persistence.varstore;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkArgument;

public final class SingleUserFileVarStore extends FileVarStore {

    public SingleUserFileVarStore(Path storeFile) {
        super(storeFile);
    }

    @Override
    public VarStore forUser(String userId) {
        checkArgument(userId.isEmpty(), "In single-user mode, userId must be empty but was: %s", userId);
        return this;
    }
}
