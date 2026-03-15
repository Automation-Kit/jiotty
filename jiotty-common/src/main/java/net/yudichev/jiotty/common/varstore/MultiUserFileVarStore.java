package net.yudichev.jiotty.common.varstore;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public final class MultiUserFileVarStore extends FileVarStore {

    public MultiUserFileVarStore(Path storeFile) {
        super(storeFile);
    }

    @Override
    public VarStore forUser(String userId) {
        checkNotNull(userId, "userId");
        checkArgument(!userId.isBlank(), "userId must not be blank");
        return new UserScopedVarStore(this, userId + '.');
    }
}
