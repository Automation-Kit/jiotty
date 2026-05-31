package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Closeable;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface Option<T> {

    int DEFAULT_FORM_ORDER = 50;

    OptionMeta<T> meta();

    TypeToken<T> getValueType();

    int getFormOrder();

    OptionDto toDtoUnsafe();

    CompletableFuture<OptionDto> toDto();

    default Optional<T> getValue() {
        return Optional.ofNullable(value());
    }

    @Nullable T value();

    default T requireValue() {
        return getValue().orElseThrow(() -> new IllegalStateException(meta().key() + " is required"));
    }

    Closeable addChangeListener(Consumer<Option<T>> listener);

    CompletableFuture<T> setValue(T value);

    /// @return the response to be sent to the UI. If completed successfully, the object will be serialised to JSON and written to the response stream
    CompletableFuture<?> onFormSubmit(Optional<String> value);

    void applyDefault();

    T setValueSync(T value);
}
