package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface Option<T> {

    int DEFAULT_FORM_ORDER = 50;

    OptionMeta<T> meta();

    TypeToken<T> getValueType();

    int getFormOrder();

    OptionDtos.OptionDto toDto();

    Optional<T> getValue();

    default T requireValue() {
        return getValue().orElseThrow(() -> new IllegalStateException(meta().key() + " is required"));
    }

    Closeable addChangeListener(Consumer<Option<T>> listener);

    CompletableFuture<Void> setValue(T value);

    CompletableFuture<Void> onFormSubmit(Optional<String> value);

    void applyDefault();

    void setValueSync(T value);
}
