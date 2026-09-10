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

    /// Applies a value submitted from a form. A value this option refuses comes back as [FormSubmitResult.Rejected] — the submitter's to correct — so a failed
    /// future here means the server broke, never that the value was bad.
    CompletableFuture<FormSubmitResult> onFormSubmit(Optional<String> value);

    void applyDefault();

    T setValueSync(T value);
}
