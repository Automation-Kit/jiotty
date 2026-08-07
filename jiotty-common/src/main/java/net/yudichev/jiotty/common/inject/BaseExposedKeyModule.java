package net.yudichev.jiotty.common.inject;

import com.google.inject.Key;

import static com.google.common.base.Preconditions.checkNotNull;

/// Base for a module whose purpose is to publish exactly one service, with the exposed key derived from the [SpecifiedAnnotation] its builder carries. Bind and
/// expose [#exposedKey] in [#configure()] rather than naming the raw type, so a caller can disambiguate the binding with
/// [BaseModuleBuilder#withAnnotation(SpecifiedAnnotation)] when the exposed type already has another binding in the parent injector.
///
/// A module whose exposed key is not the plain annotated form of its type parameter — a reified generic, or one delegated to a nested module — implements
/// [ExposedKeyModule] directly instead.
public abstract class BaseExposedKeyModule<T> extends BaseLifecycleComponentModule implements ExposedKeyModule<T> {
    protected final Key<T> exposedKey;

    protected BaseExposedKeyModule(SpecifiedAnnotation specifiedAnnotation) {
        exposedKey = checkNotNull(specifiedAnnotation).specify(ExposedKeyModule.super.getExposedKey().getTypeLiteral());
    }

    @Override
    public final Key<T> getExposedKey() {
        return exposedKey;
    }
}
