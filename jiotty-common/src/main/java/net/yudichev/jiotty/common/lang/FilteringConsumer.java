package net.yudichev.jiotty.common.lang;

import java.util.function.Consumer;
import java.util.function.Predicate;

import static com.google.common.base.Preconditions.checkNotNull;

/// A [Consumer] that passes each value for which `filter` returns `true` to `delegate`, and drops every other value.
///
/// @param <T> the type of the values
public final class FilteringConsumer<T> implements Consumer<T> {
    private final Consumer<? super T> delegate;
    private final Predicate<? super T> filter;

    private FilteringConsumer(Consumer<? super T> delegate, Predicate<? super T> filter) {
        this.delegate = checkNotNull(delegate);
        this.filter = checkNotNull(filter);
    }

    /// Returns a [Consumer] that passes each value for which `filter` returns `true` to `delegate`, and drops every other value.
    ///
    /// @param <T> the type of the values
    public static <T> FilteringConsumer<T> filtering(Consumer<? super T> delegate, Predicate<? super T> filter) {
        return new FilteringConsumer<>(delegate, filter);
    }

    @Override
    public void accept(T value) {
        if (filter.test(value)) {
            delegate.accept(value);
        }
    }
}
