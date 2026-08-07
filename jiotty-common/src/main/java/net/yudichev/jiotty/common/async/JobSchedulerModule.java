package net.yudichev.jiotty.common.async;

import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.time.ZoneId;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class JobSchedulerModule extends BaseExposedKeyModule<JobScheduler> {
    private final BindingSpec<ZoneId> zoneIdSpec;

    private JobSchedulerModule(BindingSpec<ZoneId> zoneIdSpec, SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.zoneIdSpec = checkNotNull(zoneIdSpec);
    }

    @Override
    protected void configure() {
        zoneIdSpec.bind(ZoneId.class).annotatedWith(JobSchedulerImpl.Dependency.class).installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(registerLifecycleComponent(JobSchedulerImpl.class));
        expose(exposedKey);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BaseModuleBuilder<JobScheduler, Builder> {
        private BindingSpec<ZoneId> zoneIdSpec = literally(ZoneId.systemDefault());

        public Builder withZoneId(BindingSpec<ZoneId> zoneIdSpec) {
            this.zoneIdSpec = checkNotNull(zoneIdSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<JobScheduler> build() {
            return new JobSchedulerModule(zoneIdSpec, specifiedAnnotation());
        }
    }
}
