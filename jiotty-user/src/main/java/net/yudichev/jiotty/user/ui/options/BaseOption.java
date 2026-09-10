package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public abstract class BaseOption<T> implements Option<T>, StringFormattable {
    /// The answer to a body no option can read. It carries nothing about the value that produced it, so one instance serves every refusal.
    private static final FormSubmitResult INVALID_BODY = FormSubmitResult.rejected(OptionRejectionReasons.INVALID_VALUE);

    private final TaskExecutor executor;
    private final OptionMeta<T> meta;
    private final Listeners<Option<T>> changeListeners = new Listeners<>();
    private @Nullable T value;

    protected BaseOption(TaskExecutor executor, OptionMeta<T> meta) {
        this.executor = checkNotNull(executor);
        this.meta = checkNotNull(meta);
    }

    @Override
    public OptionMeta<T> meta() {
        return meta;
    }

    @Override
    public final TypeToken<T> getValueType() {
        return new TypeToken<>(getClass()) {};
    }

    @Override
    public final int getFormOrder() {
        return meta().formOrder();
    }

    @Override
    public CompletableFuture<OptionDto> toDto() {
        return executor.submit(this::toDtoUnsafe);
    }

    @Override
    public final @Nullable T value() {
        return value;
    }

    @Override
    public Closeable addChangeListener(Consumer<Option<T>> listener) {
        return changeListeners.addListener(executor, () -> Optional.of(this), listener);
    }

    @Override
    public final CompletableFuture<T> setValue(T value) {
        return executor.submit(() -> setValueSync(value));
    }

    /// Why this option refuses `value`, or empty when it accepts it. Overriding it states the rule once: a form submission answers with the refusal, and
    /// [#setValueSync(T)] throws it.
    ///
    /// @param value the submitted value, `null` where the form cleared the option
    protected Optional<OptionRejection> validate(@Nullable T value) {
        return Optional.empty();
    }

    /// Applies a value submitted from a form, answering with the saved value as the client displays it.
    protected final CompletableFuture<FormSubmitResult> submit(T value) {
        return submit(value, saved -> saved);
    }

    /// Applies a value submitted from a form, answering with `responseMapper` applied to the saved value.
    ///
    /// @param responseMapper renders the saved value for the client; may answer `null` where the option holds nothing
    protected final CompletableFuture<FormSubmitResult> submit(T value, Function<T, @Nullable Object> responseMapper) {
        return submit(() -> value, responseMapper);
    }

    /// Applies a value submitted as a JSON body that is already this option's own value.
    ///
    /// @param type what the body is expected to hold
    protected final CompletableFuture<FormSubmitResult> submitJson(Optional<String> body, Class<? extends T> type) {
        return submitJson(body, type, value -> value);
    }

    /// Applies a value submitted as a JSON body. A body that is absent or will not parse is refused rather than failed: a client sending the wrong shape is
    /// not a fault of ours, and the body is whatever the form held, so nothing of it is quoted back.
    ///
    /// @param type        what the body is expected to hold
    /// @param valueMapper turns that into this option's own value, on this option's executor
    protected final <B> CompletableFuture<FormSubmitResult> submitJson(Optional<String> body, Class<B> type, Function<B, T> valueMapper) {
        return body.flatMap(encoded -> Json.tryParse(encoded, type))
                   .map(parsed -> submit(() -> valueMapper.apply(parsed), saved -> saved))
                   .orElseGet(() -> completedFuture(INVALID_BODY));
    }

    /// Validates and stores the value `valueSupplier` produces, all in one task on this option's executor — so a value derived from the one already stored
    /// reads it and writes its successor in the same turn. Use this overload wherever the submitted value is built from [#value()].
    ///
    /// @param valueSupplier  produces the value to store, on this option's executor
    /// @param responseMapper renders the saved value for the client; may answer `null` where the option holds nothing
    protected final CompletableFuture<FormSubmitResult> submit(Supplier<? extends T> valueSupplier, Function<T, @Nullable Object> responseMapper) {
        return executor.submit(() -> {
            T value = valueSupplier.get();
            return validate(value)
                    .map(FormSubmitResult::rejected)
                    .orElseGet(() -> FormSubmitResult.accepted(responseMapper.apply(store(value))));
        });
    }

    @Override
    public void applyDefault() {
        setValueSync(meta.defaultValue().orElse(null));
    }

    /// Applies a value [#validate(T)] has accepted, returning the enriched form to store.
    ///
    /// @return the value to hold, `null` where the option holds nothing
    public abstract @Nullable T onChanged();

    @Override
    public String toString() {
        return toString(32);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, meta.key());
        Append.to(appendable, '=');
        Append.to(appendable, value);
    }

    @Override
    public T setValueSync(T value) {
        validate(value).ifPresent(rejection -> {
            throw rejection.toException();
        });
        return store(value);
    }

    /// Stores a value [#validate(T)] has already accepted, enriching it through [#onChanged()] and putting the previous value back if that fails.
    ///
    /// @return the value as submitted, which is what a caller asked to store — [#value()] holds the enriched one
    private T store(T value) {
        if (!Objects.equals(this.value, value)) {
            T oldValue = this.value;
            this.value = value;
            try {
                this.value = onChanged();
            } catch (RuntimeException e) {
                this.value = oldValue;
                throw e;
            }
            changeListeners.notify(this);
        }
        return value;
    }
}
