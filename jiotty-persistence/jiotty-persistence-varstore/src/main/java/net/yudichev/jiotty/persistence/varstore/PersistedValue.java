package net.yudichev.jiotty.persistence.varstore;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/// One [VarStore] row holding a single value, cached in memory so [#get()] answers from the field. Rendering delegates to the value, so a value carrying
/// personal data redacts itself in its own [StringFormattable#formatTo].
///
/// @param <T> the value the row holds
public final class PersistedValue<T> implements StringFormattable {
    private final VarStore varStore;
    private final String varStoreKey;
    private final TypeToken<T> type;
    private @Nullable T value;

    public PersistedValue(VarStore varStore, String varStoreKey, TypeToken<T> type) {
        this.varStore = checkNotNull(varStore);
        this.varStoreKey = checkNotNull(varStoreKey);
        this.type = checkNotNull(type);
    }

    public PersistedValue(VarStore varStore, String varStoreKey, Class<T> type) {
        this(varStore, varStoreKey, TypeToken.of(type));
    }

    /// Reads the persisted value back. Callers must invoke this once, before any other method.
    public void restore() {
        value = varStore.readValue(type, varStoreKey).orElse(null);
    }

    /// @return what the row holds, empty where it holds nothing
    public Optional<T> get() {
        return Optional.ofNullable(value);
    }

    public void set(T newValue) {
        value = checkNotNull(newValue);
        varStore.saveValue(varStoreKey, newValue);
    }

    public void clear() {
        if (value != null) {
            value = null;
            varStore.clearValue(varStoreKey);
        }
    }

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, value);
    }
}
