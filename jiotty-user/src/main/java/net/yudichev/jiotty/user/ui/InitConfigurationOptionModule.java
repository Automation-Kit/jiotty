package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeParameter;
import com.google.common.reflect.TypeToken;
import com.google.inject.Key;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponentModule;
import net.yudichev.jiotty.common.inject.TypeLiterals;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistenceImpl;

import static com.google.common.base.Preconditions.checkNotNull;

/// Registers a single [Option] of an arbitrary type and [restarts][ApplicationLifecycleControl#initiateRestart()] the application when the option changes
/// (after a short debounce period).
public class InitConfigurationOptionModule<T> extends BaseLifecycleComponentModule {
    private final T value;
    private final Key<ApplicationLifecycleControl> applicationLifecycleControlKey;
    private final OptionType optionType;
    private final OptionMeta<T> optionMeta;
    private final TypeToken<T> optionValueType;

    public InitConfigurationOptionModule(VarStore varStore,
                                         Key<ApplicationLifecycleControl> applicationLifecycleControlKey,
                                         OptionType optionType,
                                         OptionMeta<T> optionMeta) {
        this.applicationLifecycleControlKey = checkNotNull(applicationLifecycleControlKey);
        this.optionType = checkNotNull(optionType);
        this.optionMeta = checkNotNull(optionMeta);
        optionValueType = new TypeToken<>(getClass()) {};
        value = new OptionPersistenceImpl(varStore).load(optionValueType, optionMeta.key()).or(optionMeta::defaultValue).orElse(null);
    }

    public final T optionValue() {
        return value;
    }

    @Override
    protected void configure() {
        bind(ApplicationLifecycleControl.class).annotatedWith(InitConfigurationOptionManager.Dependency.class).to(applicationLifecycleControlKey);
        bind(OptionType.class).annotatedWith(InitConfigurationOptionManager.Dependency.class).toInstance(optionType);
        bind(TypeLiterals.asTypeLiteral(new TypeToken<OptionMeta<T>>() {}
                                                .where(new TypeParameter<>() {}, optionValueType)))
                .annotatedWith(InitConfigurationOptionManager.Dependency.class).toInstance(optionMeta);
        registerLifecycleComponent(TypeLiterals.asTypeLiteral(new TypeToken<InitConfigurationOptionManager<T>>() {}
                                                                      .where(new TypeParameter<>() {}, optionValueType)));
    }
}
