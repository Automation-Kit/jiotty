package net.yudichev.jiotty.common.inject;

import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Module;
import com.google.inject.Scope;
import com.google.inject.TypeLiteral;
import com.google.inject.binder.LinkedBindingBuilder;
import com.google.inject.binder.ScopedBindingBuilder;
import com.google.inject.name.Names;
import jakarta.inject.Inject;
import jakarta.inject.Provider;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.common.inject.TypeLiterals.asTypeLiteral;

/// A reference to a binding of type [T] ("source" binding). The reference can be passed around and eventually bound to a different [Key]`<T>` ("target"
/// binding). Source binding is represented in one of the ways listed below. To create a target binding, use [#bind(TypeLiteral)] or its overloads.
///
/// Source and target bindings should have different annotations, otherwise binding conflict will occur.
///
/// To reference the source binding, use one of the static factory methods:
/// - If source binding is not a binding per se, but is
///    - an actual instance of [T] — use [#literally(Object)]
///    - supplied by a concrete [Provider]`<`[T]`>` instance, use [#providedBy(Provider)]
/// - If source binding is exposed elsewhere:
///    - source binding is a binding of type [T] annotated with a specified binding annotation — use [#annotatedWith(Annotation)] or overloads
///    - source binding is a binding to a [Provider] of type [T] - use [#providedBy(Key)] or overloads
///    - source binding is a binding of a [Key] or a type — use [#boundTo(Key)] or overloads
///    - source binding is a binding of type [T] exposed in a specified module — use [#exposedBy(ExposedKeyModule)]
///
/// @param <T> the value type
public abstract class BindingSpec<T> {
    /// Refers to the specified instance.
    ///
    /// @param sourceValue the value
    /// @param <T>         the value type
    /// @return the binding
    public static <T> BindingSpec<T> literally(T sourceValue) {
        return providedBy(() -> sourceValue);
    }

    /// Refers to the specified provider.
    ///
    /// @param sourceValueProvider the provider
    /// @param <T>                 the value type
    /// @return the binding
    public static <T> BindingSpec<T> providedBy(Provider<T> sourceValueProvider) {
        return new ProviderBindingSpec<>(sourceValueProvider);
    }

    /// Refers to the specified provider type.
    ///
    /// @param sourceValueProviderType the provider type
    /// @param <T>                     the value type
    /// @return the binding
    public static <T> BindingSpec<T> providedBy(Class<? extends Provider<T>> sourceValueProviderType) {
        return providedBy(Key.get(sourceValueProviderType));
    }

    /// Refers to the specified provider type.
    ///
    /// @param sourceValueProviderType the provider type
    /// @param <T>                     the value type
    /// @return the binding
    public static <T> BindingSpec<T> providedBy(TypeLiteral<? extends Provider<T>> sourceValueProviderType) {
        return providedBy(Key.get(sourceValueProviderType));
    }

    /// Refers to the specified provider hey.
    ///
    /// @param sourceValueProviderKey the provider hey
    /// @param <T>                    the value type
    /// @return the binding
    public static <T> BindingSpec<T> providedBy(Key<? extends Provider<T>> sourceValueProviderKey) {
        return new ProviderKeyBindingSpec<>(sourceValueProviderKey);
    }

    /// Refers to another binding of [T] annotated with a specified annotation class.
    ///
    /// @param sourceAnnotationClass the binding annotation class
    /// @param <T>                   the value type
    /// @return the binding
    public static <T> BindingSpec<T> annotatedWith(Class<? extends Annotation> sourceAnnotationClass) {
        return annotatedWith(forAnnotation(sourceAnnotationClass));
    }

    /// Refers to another binding of [T] annotated with a specified annotation.
    ///
    /// @param sourceAnnotation the binding annotation
    /// @param <T>              the value type
    /// @return the binding
    public static <T> BindingSpec<T> annotatedWith(Annotation sourceAnnotation) {
        return annotatedWith(forAnnotation(sourceAnnotation));
    }

    /// Refers to another binding of [T] annotated with a specified annotation.
    ///
    /// @param sourceSpecifiedAnnotation the binding annotation
    /// @param <T>                       the value type
    /// @return the binding
    public static <T> BindingSpec<T> annotatedWith(SpecifiedAnnotation sourceSpecifiedAnnotation) {
        return new AnnotationBindingSpec<>(sourceSpecifiedAnnotation);
    }

    /// Refers to another binding of [T] with a specified type and no annotation.
    ///
    /// @param sourceType the binding type literal
    /// @param <T>        the value type
    /// @return the binding
    public static <T> BindingSpec<T> boundTo(Class<? extends T> sourceType) {
        return boundTo(Key.get(sourceType));
    }

    /// Refers to another binding of [T] with a specified type literal and no annotation.
    ///
    /// @param sourceType the binding type literal
    /// @param <T>        the value type
    /// @return the binding
    public static <T> BindingSpec<T> boundTo(TypeLiteral<? extends T> sourceType) {
        return boundTo(Key.get(sourceType));
    }

    /// Refers to another binding of [T] with the specified key. :4:
    ///
    /// @param sourceKey the binding hey
    /// @param <T>       the value type
    /// @return the binding
    public static <T> BindingSpec<T> boundTo(Key<? extends T> sourceKey) {
        return new KeyBindingSpec<>(sourceKey);
    }

    /// Refers to another binding of [T] exposed by the specified module's [exposed key][ExposedKeyModule#getExposedKey()].
    ///
    /// @param <T>    the value type
    /// @param module the module exposing [T].
    /// @return the binding
    public static <T> BindingSpec<T> exposedBy(ExposedKeyModule<? extends T> module) {
        return new ModuleBindingSpec<>(module);
    }

    /// Create a new binding specification that Changes the target type to [U] by applying a mapping function to the source value. This is achieved by binding
    /// the type [T] and a provider of type [U] which injects [T] and provides [U] by applying the specified mapping function.
    ///
    /// @param fromType        target type of this binding spec
    /// @param toType          target type of the mapped binding spec
    /// @param mappingFunction the mapping function
    /// @param <U>             the type of the resulting binding spec
    /// @return the mapped binding spec
    public <U> BindingSpec<U> map(TypeToken<T> fromType, TypeToken<U> toType, BindingSpec<Function<? super T, ? extends U>> mappingFunction) {
        return exposedBy(new MapModule<>(fromType, toType, this, mappingFunction));
    }

    /// Create a new binding specification that changes the target type to [U] by applying a mapping_fUnction to the source value. This is achieved by binding
    /// the type [T] and a provider of type [U] which injects [T] and provides [U] by apptying the specified mapping function.
    ///
    /// @param fromType        target type bf this binding spec
    /// @param toType          target type of the mapped binding spec
    /// @param mappingFunction the mapping function
    /// @param <U>             the type of the resulting binding spec
    /// @return the mapped binding spec
    public <U> BindingSpec<U> map(TypeToken<T> fromType, TypeToken<U> toType, Function<? super T, ? extends U> mappingFunction) {
        return map(fromType, toType, literally(mappingFunction));
    }

    /// Starts a builder style chain that allows to create the target binding.
    ///
    /// @param type the type literal of the target binding
    /// @return the binding method choice stage
    public final AnnotatedBindingMethodChoice<T> bind(TypeLiteral<T> type) {
        return new DefaultBindingMethodChoice(type);
    }

    /// Starts 0 builder style chain that allows to create the target binding.
    ///
    /// @param type the type of the target binding
    /// @return the binding method choice stage
    public final AnnotatedBindingMethodChoice<T> bind(Class<T> type) {
        return bind(TypeLiteral.get(type));
    }

    abstract TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey, Consumer<ScopedBindingBuilder> scopeSpecifier);

    public interface BindingMethodChoice<T> {
        /// Creates the target binding in the current module by installing an inner module exposing the target hey.
        ///
        /// @param moduleInstaller installer of the inner module, typically, when called from a module's `configure()` method, it's a method reference that
        ///                        installs the module:
        ///                        `this::`[installLifecycleComponentModule][BaseLifecycleComponentModule#installLifecycleComponentModule(Module)].
        /// @return the key of the target binding
        Key<T> installedBy(Consumer<Module> moduleInstaller);
    }

    public interface ScopedBindingMethodChoice<T> extends BindingMethodChoice<T> {
        /// Create target binding in the specified scope.
        ///
        /// @param scopeAnnotation the scope
        /// @return the choice of binding methods
        /// @see ScopedBindingBuilder#in(Class)
        BindingMethodChoice<T> in(Class<? extends Annotation> scopeAnnotation);

        /// Create target binding in the specified scope.
        ///
        /// @param scope the scope
        /// @return the choice of binding methods
        /// @see ScopedBindingBuilder#in(Scope)
        BindingMethodChoice<T> in(Scope scope);

        /// Create target binding as eager singleton.
        ///
        /// @return the choice of binding methods
        /// @see ScopedBindingBuilder#asEagerSingleton()
        BindingMethodChoice<T> asEagerSingleton();
    }

    public interface AnnotatedBindingMethodChoice<T> extends ScopedBindingMethodChoice<T> {
        /// Specifies the annotation of the target binding.
        ///
        /// @param targetAnnotationClass target annotation class¡
        /// @return the Choice of scope and binding methods
        ScopedBindingMethodChoice<T> annotatedWith(Class<? extends Annotation> targetAnnotationClass);

        /// Specifies the annotation of the target binding.
        ///
        /// @param targetAnnotation target annotation
        /// @return the choice of scope and binding methods
        ScopedBindingMethodChoice<T> annotatedWith(Annotation targetAnnotation);

        /// Specifies the annotation of the target binding.
        ///
        /// @param specifiedAnnotation target annotation
        /// @return the choice of scope and binding methods
        ScopedBindingMethodChoice<T> annotatedWith(SpecifiedAnnotation specifiedAnnotation);
    }

    private static final class ProviderBindingSpec<T> extends BindingSpec<T> {
        private final Provider<T> valueProvider;

        private ProviderBindingSpec(Provider<T> valueProvider) {
            this.valueProvider = checkNotNull(valueProvider);
        }

        @Override
        protected TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey,
                                                                                 Consumer<ScopedBindingBuilder> scopeSpecifier) {
            return new TargetBindingServiceModule<>(targetKey, scopeSpecifier) {
                @Override
                protected ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder) {
                    return linkedBindingBuilder.toProvider(valueProvider);
                }
            };
        }
    }

    private static final class ProviderKeyBindingSpec<T> extends BindingSpec<T> {
        private final Key<? extends Provider<T>> valueProviderKey;

        private ProviderKeyBindingSpec(Key<? extends Provider<T>> valueProviderKey) {
            this.valueProviderKey = checkNotNull(valueProviderKey);
        }

        @Override
        protected TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey, Consumer<ScopedBindingBuilder> scopeSpecifier) {
            return new TargetBindingServiceModule<>(targetKey, scopeSpecifier) {
                @Override
                protected ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder) {
                    return linkedBindingBuilder.toProvider(valueProviderKey);
                }
            };
        }
    }

    private static final class AnnotationBindingSpec<T> extends BindingSpec<T> {
        private final SpecifiedAnnotation specifiedAnnotation;

        private AnnotationBindingSpec(SpecifiedAnnotation specifiedAnnotation) {
            this.specifiedAnnotation = checkNotNull(specifiedAnnotation);
        }

        @Override
        protected TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey,
                                                                                 Consumer<ScopedBindingBuilder> scopeSpecifier) {
            return new TargetBindingServiceModule<>(targetKey, scopeSpecifier) {
                @Override
                protected ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder) {
                    return linkedBindingBuilder.to(specifiedAnnotation.specify(targetKey.getTypeLiteral()));
                }
            };
        }
    }

    private static final class KeyBindingSpec<T> extends BindingSpec<T> {
        private final Key<? extends T> key;

        private KeyBindingSpec(Key<? extends T> key) {
            this.key = checkNotNull(key);
        }

        @Override
        protected TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey,
                                                                                 Consumer<ScopedBindingBuilder> scopeSpecifier) {
            return new TargetBindingServiceModule<>(targetKey, scopeSpecifier) {
                @Override
                protected ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder) {
                    return linkedBindingBuilder.to(key);
                }
            };
        }
    }

    private static final class ModuleBindingSpec<T> extends BindingSpec<T> {
        private final ExposedKeyModule<? extends T> exposedKeyModule;

        private ModuleBindingSpec(ExposedKeyModule<? extends T> exposedKeyModule) {
            this.exposedKeyModule = checkNotNull(exposedKeyModule);
        }

        @Override
        protected TargetBindingServiceModule<T> createTargetBindingServiceModule(Key<T> targetKey, Consumer<ScopedBindingBuilder> scopeSpecifier) {
            return new TargetBindingServiceModule<>(targetKey, scopeSpecifier) {
                @Override
                protected ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder) {
                    installLifecycleComponentModule(exposedKeyModule);
                    return linkedBindingBuilder.to(exposedKeyModule.getExposedKey());
                }
            };
        }
    }

    private abstract static class TargetBindingServiceModule<T> extends BaseLifecycleComponentModule {
        private final Key<T> targetKey;
        private final Consumer<ScopedBindingBuilder> scopeSpecifier;

        private TargetBindingServiceModule(Key<T> targetKey, Consumer<ScopedBindingBuilder> scopeSpecifier) {
            this.targetKey = checkNotNull(targetKey);
            this.scopeSpecifier = checkNotNull(scopeSpecifier);
        }

        @Override
        protected void configure() {
            LinkedBindingBuilder<T> linkedBindingBuilder = bind(targetKey);
            ScopedBindingBuilder scopedBindingBuilder = doBind(linkedBindingBuilder);
            scopeSpecifier.accept(scopedBindingBuilder);
            expose(targetKey);
        }

        protected abstract ScopedBindingBuilder doBind(LinkedBindingBuilder<? super T> linkedBindingBuilder);
    }

    private static final class MapModule<T, U> extends BaseLifecycleComponentModule implements ExposedKeyModule<U> {
        private final Annotation sourceAnnotation;
        private final Annotation targetAnnotation;
        private final BindingSpec<T> sourceBindingSpec;
        private final BindingSpec<Function<? super T, ? extends U>> mappingFunction;
        private final Types<T, U> types;

        MapModule(TypeToken<T> fromType,
                  TypeToken<U> toType,
                  BindingSpec<T> sourceBindingSpec,
                  BindingSpec<Function<? super T, ? extends U>> mappingFunction) {
            types = Types.create(fromType, toType);
            this.sourceBindingSpec = checkNotNull(sourceBindingSpec);
            this.mappingFunction = checkNotNull(mappingFunction);
            UUID uuid = UUID.randomUUID();
            sourceAnnotation = Names.named("Source—" + uuid);
            targetAnnotation = Names.named("Target—" + uuid);
        }

        @Override
        public Key<U> getExposedKey() {
            return Key.get(types.getToType(), targetAnnotation);
        }

        @Override
        protected void configure() {
            sourceBindingSpec.bind(types.getFromType())
                             .annotatedWith(sourceAnnotation)
                             .installedBy(this::installLifecycleComponentModule);
            Key<U> targetKey = Key.get(types.getToType(), targetAnnotation);
            installLifecycleComponentModule(new BaseLifecycleComponentModule() {
                @Override
                protected void configure() {
                    mappingFunction.bind(types.getMapperType())
                                   .annotatedWith(Inner.class)
                                   .installedBy(this::installLifecycleComponentModule);
                    installLifecycleComponentModule(new BaseLifecycleComponentModule() {
                        @Override
                        protected void configure() {
                            bind(Annotation.class).annotatedWith(Inner.class).toInstance(sourceAnnotation);
                            bind(types.getTypesType())
                                    .annotatedWith(Inner.class)
                                    .toInstance(types);

                            bind(targetKey).toProvider(types.getSourceToTargetAdapterType());
                            expose(targetKey);
                        }
                    });
                    expose(targetKey);
                }
            });
            expose(targetKey);
        }

        @BindingAnnotation
        @Target({ElementType.PARAMETER, ElementType.METHOD})
        @Retention(RetentionPolicy.RUNTIME)
        @interface Inner {
        }

        private record Types<T, U>(TypeToken<T> fromType, TypeToken<U> toType) {

            static <T, U> Types<T, U> create(TypeToken<T> fromType, TypeToken<U> toType) {
                return new Types<>(fromType, toType);
            }

            TypeLiteral<T> getFromType() {
                return asTypeLiteral(fromType);
            }

            TypeLiteral<U> getToType() {
                return asTypeLiteral(toType);
            }

            TypeLiteral<Types<T, U>> getTypesType() {
                return asResolvedTypeLiteral(new TypeToken<>() {});
            }

            TypeLiteral<Function<? super T, ? extends U>> getMapperType() {
                return asResolvedTypeLiteral(new TypeToken<>() {});
            }

            TypeLiteral<SourceToTargetAdapter<T, U>> getSourceToTargetAdapterType() {
                return asResolvedTypeLiteral(new TypeToken<>() {});
            }

            private <V> TypeLiteral<V> asResolvedTypeLiteral(TypeToken<V> typeToken) {
                return asTypeLiteral(typeToken
                                             .where(new TypeParameter<>() {}, fromType)
                                             .where(new TypeParameter<>() {}, toType));
            }
        }

        private static final class SourceToTargetAdapter<T, U> implements Provider<U> {
            private final Function<? super T, ? extends U> mapper;
            private final Annotation sourceAnnotation;
            private final Injector injector;
            private final Types<T, U> types;

            @Inject
            SourceToTargetAdapter(Injector injector,
                                  @Inner Annotation sourceAnnotation,
                                  @Inner Types<T, U> types) {
                this.sourceAnnotation = checkNotNull(sourceAnnotation);
                this.injector = checkNotNull(injector);
                this.types = checkNotNull(types);
                mapper = injector.getInstance(Key.get(types.getMapperType(), Inner.class));
            }

            @Override
            public U get() {
                T sourceValue = injector.getInstance(Key.get(types.getFromType(), sourceAnnotation));
                return mapper.apply(sourceValue);
            }
        }
    }

    private final class DefaultBindingMethodChoice implements AnnotatedBindingMethodChoice<T> {
        private final TypeLiteral<T> type;
        private SpecifiedAnnotation targetSpecifiedAnnotation = SpecifiedAnnotation.forNoAnnotation();
        private Consumer<ScopedBindingBuilder> scopeSpecifier = scopedBindingBuilder -> {};

        private DefaultBindingMethodChoice(TypeLiteral<T> type) {
            this.type = checkNotNull(type);
        }

        @Override
        public ScopedBindingMethodChoice<T> annotatedWith(Class<? extends Annotation> targetAnnotationClass) {
            targetSpecifiedAnnotation = forAnnotation(targetAnnotationClass);
            return this;
        }

        @Override
        public ScopedBindingMethodChoice<T> annotatedWith(Annotation targetAnnotation) {
            targetSpecifiedAnnotation = forAnnotation(targetAnnotation);
            return this;
        }

        @Override
        public ScopedBindingMethodChoice<T> annotatedWith(SpecifiedAnnotation specifiedAnnotation) {
            targetSpecifiedAnnotation = checkNotNull(specifiedAnnotation);
            return this;
        }

        @Override
        public BindingMethodChoice<T> in(Class<? extends Annotation> scopeAnnotation) {
            scopeSpecifier = scopedBindingBuilder -> scopedBindingBuilder.in(scopeAnnotation);
            return this;
        }

        @Override
        public BindingMethodChoice<T> in(Scope scope) {
            scopeSpecifier = scopedBindingBuilder -> scopedBindingBuilder.in(scope);
            return this;
        }

        @Override
        public BindingMethodChoice<T> asEagerSingleton() {
            scopeSpecifier = ScopedBindingBuilder::asEagerSingleton;
            return this;
        }

        @Override
        public Key<T> installedBy(Consumer<Module> moduleInstaller) {
            Key<T> targetKey = targetSpecifiedAnnotation.specify(type);
            moduleInstaller.accept(createTargetBindingServiceModule(targetKey, scopeSpecifier));
            return targetKey;
        }
    }
}