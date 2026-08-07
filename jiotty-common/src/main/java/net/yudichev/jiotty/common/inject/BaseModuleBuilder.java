package net.yudichev.jiotty.common.inject;

import net.yudichev.jiotty.common.lang.TypedBuilder;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forNoAnnotation;

public abstract class BaseModuleBuilder<T, B extends BaseModuleBuilder<T, B>> implements TypedBuilder<ExposedKeyModule<T>>, HasWithAnnotation {
    private SpecifiedAnnotation specifiedAnnotation = forNoAnnotation();

    /// A builder for a module with nothing to configure but its annotation, so it needs no nested `Builder` class of its own — the module's `builder()` factory
    /// returns this, handing the carried [SpecifiedAnnotation] to `moduleFactory` when [#build()] is called:
    ///
    /// ```
    /// public static BaseModuleBuilder<OutdoorLightService, ?> builder() {
    ///     return simpleBuilder(OutdoorLightServiceModule::new);
    /// }
    /// ```
    ///
    /// A module that takes any other builder parameter declares its own `Builder extends BaseModuleBuilder<T, Builder>` instead, so each parameter gets a
    /// named setter.
    public static <T, B extends BaseModuleBuilder<T, B>> BaseModuleBuilder<T, B> simpleBuilder(
            Function<SpecifiedAnnotation, ? extends ExposedKeyModule<T>> moduleFactory) {
        return new BaseModuleBuilder<>() {
            @Override
            public ExposedKeyModule<T> build() {
                return moduleFactory.apply(specifiedAnnotation());
            }
        };
    }

    @SuppressWarnings("unchecked") // safe: B is always the concrete subclass
    @Override
    public B withAnnotation(SpecifiedAnnotation specifiedAnnotation) {
        this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
        return (B) this;
    }

    protected SpecifiedAnnotation specifiedAnnotation() {
        return specifiedAnnotation;
    }
}
