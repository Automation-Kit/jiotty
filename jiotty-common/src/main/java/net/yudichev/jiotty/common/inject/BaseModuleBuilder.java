package net.yudichev.jiotty.common.inject;

import net.yudichev.jiotty.common.lang.TypedBuilder;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forNoAnnotation;

public abstract class BaseModuleBuilder<T, B extends BaseModuleBuilder<T, B>> implements TypedBuilder<ExposedKeyModule<T>>, HasWithAnnotation {
    private SpecifiedAnnotation specifiedAnnotation = forNoAnnotation();

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
