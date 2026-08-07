package net.yudichev.jiotty.connector.slide;

import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import net.yudichev.jiotty.appliance.Appliance;
import net.yudichev.jiotty.appliance.ApplianceModule;
import net.yudichev.jiotty.common.async.backoff.BackOffConfig;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.connector.slide.Bindings.SlideId;

public final class SlideApplianceModule extends ApplianceModule {
    private final BindingSpec<Long> slideIdSpec;
    private final BindingSpec<SlideService> slideServiceSpec;
    private final BindingSpec<String> nameSpec;

    private SlideApplianceModule(BindingSpec<Long> slideIdSpec,
                                 BindingSpec<SlideService> slideServiceSpec,
                                 BindingSpec<String> nameSpec,
                                 Optional<BindingSpec<BackOffConfig>> backOffConfigSpec,
                                 SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation, backOffConfigSpec);
        this.slideIdSpec = checkNotNull(slideIdSpec);
        this.slideServiceSpec = checkNotNull(slideServiceSpec);
        this.nameSpec = checkNotNull(nameSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected Key<? extends Appliance> configureDependencies() {
        slideIdSpec.bind(Long.class)
                   .annotatedWith(SlideId.class)
                   .installedBy(this::installLifecycleComponentModule);
        slideServiceSpec.bind(SlideService.class)
                        .annotatedWith(SlideAsAppliance.Dependency.class)
                        .installedBy(this::installLifecycleComponentModule);
        nameSpec.bind(String.class)
                .annotatedWith(SlideAsAppliance.Name.class)
                .installedBy(this::installLifecycleComponentModule);
        return Key.get(SlideAsAppliance.class);
    }

    public static final class Builder extends BaseModuleBuilder<Appliance, Builder> {
        private BindingSpec<SlideService> slideServiceSpec = BindingSpec.boundTo(SlideService.class);
        private BindingSpec<Long> slideIdSpec;
        private BindingSpec<String> nameSpec;
        private BindingSpec<BackOffConfig> backOffConfigSpec;

        public Builder setSlideIdSpec(BindingSpec<Long> slideIdSpec) {
            this.slideIdSpec = checkNotNull(slideIdSpec);
            return this;
        }

        public Builder withSlideService(BindingSpec<SlideService> slideServiceSpec) {
            this.slideServiceSpec = checkNotNull(slideServiceSpec);
            return this;
        }

        public Builder withName(BindingSpec<String> nameSpec) {
            this.nameSpec = checkNotNull(nameSpec);
            return this;
        }

        public Builder withRetries(BindingSpec<BackOffConfig> backOffConfigSpec) {
            this.backOffConfigSpec = checkNotNull(backOffConfigSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<Appliance> build() {
            if (nameSpec == null) {
                nameSpec = slideIdSpec.map(TypeToken.of(Long.class), TypeToken.of(String.class), Object::toString);
            }
            return new SlideApplianceModule(slideIdSpec, slideServiceSpec, nameSpec, Optional.ofNullable(backOffConfigSpec), specifiedAnnotation());
        }
    }
}
