package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;
import jakarta.inject.Inject;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

public final class OptionPersistenceImpl implements OptionPersistence {
    static final String UI_OPTIONS_KEY_PREFIX = "UiOption";

    private final VarStore varStore;

    @Inject
    public OptionPersistenceImpl(VarStore varStore) {
        this.varStore = checkNotNull(varStore);
    }

    @Override
    public void save(Option<?> option) {
        option.getValue().ifPresentOrElse(value -> save(option.meta().key(), value), () -> clear(option.meta().key()));
    }

    public void save(String optionKey, Object value) {
        varStore.saveValue(createStoreKey(optionKey), value);
    }

    public void clear(String optionKey) {
        varStore.clearValue(createStoreKey(optionKey));
    }

    @Override
    public <T> void load(Option<T> option) {
        load(option.getValueType(), option.meta().key()).ifPresentOrElse(option::setValueSync, option::applyDefault);
    }

    public <T> Optional<T> load(TypeToken<T> valueType, String optionKey) {
        return varStore.readValue(valueType, createStoreKey(optionKey));
    }

    private static String createStoreKey(String optionKey) {
        return UI_OPTIONS_KEY_PREFIX + '.' + optionKey;
    }
}
