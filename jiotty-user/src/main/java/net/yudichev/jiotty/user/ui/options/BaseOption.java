package net.yudichev.jiotty.user.ui.options;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class BaseOption<T> implements Option<T> {
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

    @Override
    public void applyDefault() {
        setValueSync(meta.defaultValue().orElse(null));
    }

    /// Process value change, validate it and return a new, enriched value
    ///
    /// @return a new updated value
    /// @throws IllegalArgumentException if value validation fails
    public abstract @Nullable T onChanged();

    @Override
    public String toString() {
        return meta.key() + '=' + value;
    }

    @Override
    public T setValueSync(T value) {
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

    protected final TaskExecutor executor() {
        return executor;
    }
}
