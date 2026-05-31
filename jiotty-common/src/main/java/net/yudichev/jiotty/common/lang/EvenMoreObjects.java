package net.yudichev.jiotty.common.lang;

import org.jspecify.annotations.Nullable;

import java.util.function.Function;

public final class EvenMoreObjects {
    private EvenMoreObjects() {
    }

    public static <T, U> U mapIfNotNull(@Nullable T value, Function<? super T, ? extends U> mapper) {
        return value == null ? null : mapper.apply(value);
    }
}
