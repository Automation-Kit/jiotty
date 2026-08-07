package net.yudichev.jiotty.common.async.backoff;

import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.inject.BaseExposedKeyModule;
import net.yudichev.jiotty.common.inject.BaseModuleBuilder;
import net.yudichev.jiotty.common.inject.BindingSpec;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.SpecifiedAnnotation;
import net.yudichev.jiotty.common.lang.backoff.BackOff;

import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

public final class BackingOffExceptionHandlerModule extends BaseExposedKeyModule<BackingOffExceptionHandler> {
    private final BindingSpec<BackOffConfig> configSpec;
    private final BindingSpec<Predicate<? super Throwable>> retryableExceptionPredicateSpec;

    private BackingOffExceptionHandlerModule(BindingSpec<Predicate<? super Throwable>> retryableExceptionPredicateSpec,
                                             BindingSpec<BackOffConfig> configSpec,
                                             SpecifiedAnnotation specifiedAnnotation) {
        super(specifiedAnnotation);
        this.retryableExceptionPredicateSpec = checkNotNull(retryableExceptionPredicateSpec);
        this.configSpec = checkNotNull(configSpec);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected void configure() {
        configSpec.bind(BackOffConfig.class)
                  .annotatedWith(BackOffProvider.Dependency.class)
                  .installedBy(this::installLifecycleComponentModule);
        bind(BackOff.class).annotatedWith(BackingOffExceptionHandlerImpl.Dependency.class).toProvider(BackOffProvider.class);
        retryableExceptionPredicateSpec.bind(new TypeLiteral<>() {})
                                       .annotatedWith(BackingOffExceptionHandlerImpl.Dependency.class)
                                       .installedBy(this::installLifecycleComponentModule);
        bind(exposedKey).to(BackingOffExceptionHandlerImpl.class);
        expose(exposedKey);
    }

    public static final class Builder extends BaseModuleBuilder<BackingOffExceptionHandler, Builder> {
        private BindingSpec<Predicate<? super Throwable>> retryableExceptionPredicateSpec;
        private BindingSpec<BackOffConfig> configSpec = literally(BackOffConfig.builder().build());

        public Builder setRetryableExceptionPredicate(BindingSpec<Predicate<? super Throwable>> retryableExceptionPredicateSpec) {
            this.retryableExceptionPredicateSpec = checkNotNull(retryableExceptionPredicateSpec);
            return this;
        }

        public Builder withConfig(BindingSpec<BackOffConfig> configSpec) {
            this.configSpec = checkNotNull(configSpec);
            return this;
        }

        @Override
        public ExposedKeyModule<BackingOffExceptionHandler> build() {
            return new BackingOffExceptionHandlerModule(retryableExceptionPredicateSpec, configSpec, specifiedAnnotation());
        }
    }
}
