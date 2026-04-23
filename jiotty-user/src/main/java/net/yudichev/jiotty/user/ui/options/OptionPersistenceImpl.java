package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.persistence.varstore.VarStore;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class OptionPersistenceImpl implements OptionPersistence {
    static final String UI_OPTIONS_KEY_PREFIX = "UiOption";

    private final VarStore varStore;

    @Inject
    public OptionPersistenceImpl(@Dependency VarStore varStore) {
        this.varStore = checkNotNull(varStore);
    }

    @Override
    public void save(Option<?> option) {
        boolean sensitive = option.meta().sensitive();
        option.getValue().ifPresentOrElse(value -> save(option.meta().key(), value, sensitive),
                                          () -> clear(option.meta().key()));
    }

    public void save(String optionKey, Object value) {
        save(optionKey, value, false);
    }

    public void save(String optionKey, Object value, boolean sensitive) {
        if (sensitive) {
            varStore.saveValueEncrypted(createStoreKey(optionKey), value);
        } else {
            varStore.saveValue(createStoreKey(optionKey), value);
        }
    }

    public void clear(String optionKey) {
        varStore.clearValue(createStoreKey(optionKey));
    }

    @Override
    public <T> void load(Option<T> option) {
        load(option.getValueType(), option.meta().key(), option.meta().sensitive())
                .ifPresentOrElse(option::setValueSync, option::applyDefault);
    }

    public <T> Optional<T> load(TypeToken<T> valueType, String optionKey) {
        return load(valueType, optionKey, false);
    }

    public <T> Optional<T> load(TypeToken<T> valueType, String optionKey, boolean sensitive) {
        return sensitive
               ? varStore.readValueEncrypted(valueType, createStoreKey(optionKey))
               : varStore.readValue(valueType, createStoreKey(optionKey));
    }

    private static String createStoreKey(String optionKey) {
        return UI_OPTIONS_KEY_PREFIX + '.' + optionKey;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }
}
