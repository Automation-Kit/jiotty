package net.yudichev.jiotty.common.lang;

import org.jspecify.annotations.Nullable;

import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkNotNull;

public final class DeduplicatingBiConsumer<T, U> implements BiConsumer<T, U> {
    private final BiConsumer<T, U> delegate;
    private @Nullable T lastValue1;
    private @Nullable U lastValue2;

    public DeduplicatingBiConsumer(BiConsumer<T, U> delegate) {
        this.delegate = checkNotNull(delegate);
    }

    @Override
    public void accept(T t, U u) {
        if (!t.equals(lastValue1) || !u.equals(lastValue2)) {
            delegate.accept(t, u);
            lastValue1 = t;
            lastValue2 = u;
        }
    }
}
