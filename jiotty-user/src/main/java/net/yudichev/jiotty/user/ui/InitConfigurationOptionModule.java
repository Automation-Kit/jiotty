package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.TypeLiterals;
import net.yudichev.jiotty.common.varstore.VarStore;

import static com.google.common.base.Preconditions.checkNotNull;

public class InitConfigurationOptionModule<T> extends BaseLifecycleComponentModule {
    private final T value;
    private final OptionType optionType;
    private final OptionMeta<T> optionMeta;
    private final TypeToken<T> optionValueType;

    public InitConfigurationOptionModule(VarStore varStore, OptionType optionType, OptionMeta<T> optionMeta) {
        this.optionType = checkNotNull(optionType);
        this.optionMeta = checkNotNull(optionMeta);
        optionValueType = new TypeToken<>(getClass()) {};
        value = new OptionPersistenceImpl(varStore).load(optionValueType, optionMeta.key()).orElse(optionMeta.defaultValue());
    }

    public final T optionValue() {
        return value;
    }

    @Override
    protected void configure() {
        bind(OptionType.class).annotatedWith(InitConfigurationOptionManager.Dependency.class).toInstance(optionType);
        bind(TypeLiterals.asTypeLiteral(new TypeToken<OptionMeta<T>>() {}
                                                .where(new TypeParameter<>() {}, optionValueType)))
                .annotatedWith(InitConfigurationOptionManager.Dependency.class).toInstance(optionMeta);
        registerLifecycleComponent(TypeLiterals.asTypeLiteral(new TypeToken<InitConfigurationOptionManager<T>>() {}
                                                                      .where(new TypeParameter<>() {}, optionValueType)));
    }
}
