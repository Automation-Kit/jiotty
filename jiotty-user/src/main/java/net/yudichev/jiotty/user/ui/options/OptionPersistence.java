package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;

import java.util.Optional;

public interface OptionPersistence {
    void save(Option<?> option);

    <T> void load(Option<T> option);

    /// Stores `value` as the value of the option with the key `optionKey`, encrypting it when `sensitive` is true.
    void save(String optionKey, Object value, boolean sensitive);

    /// @return the stored value of the option with the key `optionKey`, read as `valueType` and decrypted when `sensitive` is true, or empty when no value
    ///     is stored
    <T> Optional<T> load(TypeToken<T> valueType, String optionKey, boolean sensitive);
}
