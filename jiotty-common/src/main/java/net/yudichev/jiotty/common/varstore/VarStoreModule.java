package net.yudichev.jiotty.common.varstore;

import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.nio.file.Path;

import static com.google.common.base.Preconditions.checkNotNull;

public final class VarStoreModule extends BaseLifecycleComponentModule implements ExposedKeyModule<VarStore> {
    private final VarStoreImpl varStore;

    private VarStoreModule(Path path) {
        varStore = new VarStoreImpl(path);
    }

    public VarStoreImpl varStore() {
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

        private Builder() {
        }

        public Builder setPath(Path path) {
            this.path = checkNotNull(path);
            return this;
        }

        @Override
        public VarStoreModule build() {
            return new VarStoreModule(path);
        }
    }
}
