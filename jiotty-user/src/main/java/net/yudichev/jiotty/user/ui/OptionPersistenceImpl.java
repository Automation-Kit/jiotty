package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.varstore.VarStore;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

public final class OptionPersistenceImpl implements OptionPersistence {
    private static final String UI_OPTIONS_KEY_PREFIX = "UiOption";

    private final VarStore varStore;

    @Inject
    public OptionPersistenceImpl(VarStore varStore) {
        this.varStore = checkNotNull(varStore);
    }

    @Override
    public void save(Option<?> option) {
        save(option.meta().key(), option.requireValue());
    }

    public void save(String optionKey, Object value) {
        varStore.saveValue(UI_OPTIONS_KEY_PREFIX + '.' + optionKey, value);
    }

    @Override
    public <T> void load(Option<T> option) {
        load(option.getValueType(), option.meta().key()).ifPresentOrElse(option::setValueSync, option::applyDefault);
    }

    public <T> Optional<T> load(TypeToken<T> valueType, String optionKey) {
        return varStore.readValue(valueType, UI_OPTIONS_KEY_PREFIX + '.' + optionKey);
    }
}
