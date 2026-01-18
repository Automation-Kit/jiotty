package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeToken;
import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class BaseOption<T> implements Option<T> {
    private final TaskExecutor executor;
    private final OptionMeta<T> meta;
    private final Listeners<Option<T>> changeListeners = new Listeners<>();
    private T value;

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
    public final Optional<T> getValue() {
        return Optional.ofNullable(value);
    }

    @Override
    public Closeable addChangeListener(Consumer<Option<T>> listener) {
        return changeListeners.addListener(listener);
    }

    @Override
    public final CompletableFuture<Void> setValue(T value) {
        return executor.submit(() -> setValueSync(value));
    }

    @Override
    public void applyDefault() {
        setValueSync(meta.defaultValue());
    }

    /// Process value change, validate it and return a new, enriched value
    ///
    /// @return a new updated value
    /// @throws IllegalArgumentException if value validation fails
    @Nullable
    public abstract T onChanged();

    @Override
    public String toString() {
        return meta.key() + '=' + value;
    }

    @Override
    public void setValueSync(T value) {
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
    }
}
