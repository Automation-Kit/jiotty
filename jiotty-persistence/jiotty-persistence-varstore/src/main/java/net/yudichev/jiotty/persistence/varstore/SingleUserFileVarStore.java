package net.yudichev.jiotty.persistence.varstore;

import net.yudichev.jiotty.common.security.EnvelopeEncryption;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkArgument;

final class SingleUserFileVarStore extends BaseFileVarStore {

    public SingleUserFileVarStore(Path storeFile, @Nullable EnvelopeEncryption encryption) {
        super(storeFile, encryption);
    }

    @Override
    public VarStore forUser(String userId) {
        checkArgument(userId.isEmpty(), "In single-user mode, userId must be empty but was: %s", userId);
        return this;
    }
}
