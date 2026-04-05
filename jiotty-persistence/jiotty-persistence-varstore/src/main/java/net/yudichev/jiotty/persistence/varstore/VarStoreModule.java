package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.HasWithAnnotation;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.TypedBuilder;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;

import java.nio.file.Path;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Boolean.FALSE;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class VarStoreModule extends BaseLifecycleComponentModule implements ExposedKeyModule<VarStore> {
    private final Key<VarStore> exposedKey;
    private final @Nullable BindingSpec<Path> pathSpec;
    private final @Nullable BindingSpec<DataSourceFactory> dataSourceFactorySpec;
    private final BindingSpec<String> tableNameSpec;
    private final BindingSpec<Boolean> singleUserSpec;

    private VarStoreModule(SpecifiedAnnotation specifiedAnnotation,
                           @Nullable BindingSpec<Path> pathSpec,
                           @Nullable BindingSpec<DataSourceFactory> dataSourceFactorySpec,
                           BindingSpec<String> tableNameSpec,
                           BindingSpec<Boolean> singleUserSpec) {
        this.pathSpec = pathSpec;
        this.dataSourceFactorySpec = dataSourceFactorySpec;
        this.tableNameSpec = checkNotNull(tableNameSpec);
        this.singleUserSpec = checkNotNull(singleUserSpec);
        exposedKey = specifiedAnnotation.specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public Key<VarStore> getExposedKey() {
        return exposedKey;
    }

    @Override
    protected void configure() {
        tableNameSpec.bind(new TypeLiteral<>() {})
                     .annotatedWith(SqlVarStore.TableName.class)
                     .installedBy(this::installLifecycleComponentModule);
        singleUserSpec.bind(new TypeLiteral<>() {})
                      .annotatedWith(Bindings.SingleUser.class)
                      .installedBy(this::installLifecycleComponentModule);
        if (dataSourceFactorySpec != null) {
            dataSourceFactorySpec.bind(new TypeLiteral<>() {}).annotatedWith(SqlVarStore.Dependency.class).installedBy(this::installLifecycleComponentModule);
            BindingSpec<Optional<Path>> legacyPathSpec = pathSpec == null ? literally(Optional.empty())
                                                                          : pathSpec.map(new TypeToken<>() {},
                                                                                         new TypeToken<>() {},
                                                                                         Optional::of);
            legacyPathSpec.bind(new TypeLiteral<>() {}).annotatedWith(Bindings.ThePath.class).installedBy(this::installLifecycleComponentModule);
            bind(getExposedKey()).to(registerLifecycleComponent(SqlVarStore.class));
        } else {
            checkArgument(pathSpec != null, "At least one of 'path', 'dataSourceFactory' is required");
            pathSpec.bind(new TypeLiteral<>() {}).annotatedWith(Bindings.ThePath.class).installedBy(this::installLifecycleComponentModule);
            bind(getExposedKey()).to(FileVarStore.class);
        }

        expose(getExposedKey());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TypedBuilder<VarStoreModule>, HasWithAnnotation {

        private BindingSpec<Path> pathSpec;
        private BindingSpec<DataSourceFactory> dataSourceFactorySpec;
        private BindingSpec<String> tableNameSpec = literally("var_store");
        private BindingSpec<Boolean> singleUserSpec = literally(FALSE);
        private SpecifiedAnnotation specifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();

        private Builder() {
        }

        public Builder withPath(BindingSpec<Path> pathSpec) {
            this.pathSpec = checkNotNull(pathSpec);
            return this;
        }

        public Builder withDataSourceFactory(BindingSpec<DataSourceFactory> dataSourceFactorySpec) {
            this.dataSourceFactorySpec = checkNotNull(dataSourceFactorySpec);
            return this;
        }

        public Builder withTableName(BindingSpec<String> tableNameSpec) {
            this.tableNameSpec = checkNotNull(tableNameSpec);
            return this;
        }

        public Builder withSingleUser(BindingSpec<Boolean> singleUserSpec) {
            this.singleUserSpec = checkNotNull(singleUserSpec);
            return this;
        }

        @Override
        public Builder withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = specifiedAnnotation;
            return this;
        }

        @Override
        public VarStoreModule build() {
            return new VarStoreModule(specifiedAnnotation, pathSpec, dataSourceFactorySpec, tableNameSpec, singleUserSpec);
        }
    }
}
