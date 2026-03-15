package net.yudichev.jiotty.common.varstore;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkNotNull;

public final class VarStoreModule extends BaseLifecycleComponentModule implements ExposedKeyModule<VarStore> {
    private final FileVarStore varStore;

    private VarStoreModule(Path path, boolean singleUser) {
        varStore = singleUser ? new SingleUserFileVarStore(path) : new MultiUserFileVarStore(path);
    }

    public VarStore varStore() {
        return varStore;
    }

    @Override
    protected void configure() {
        bind(getExposedKey()).toInstance(varStore);
        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<VarStoreModule> {

        private Path path;
        private boolean singleUser;

        private Builder() {
        }

        public Builder setPath(Path path) {
            this.path = checkNotNull(path);
            return this;
        }

        public Builder withSingleUser() {
            singleUser = true;
            return this;
        }

        @Override
        public VarStoreModule build() {
            return new VarStoreModule(path, singleUser);
        }
    }
}
