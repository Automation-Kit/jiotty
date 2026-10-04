package net.yudichev.jiotty.common.lang;

public final class TestObservableValues {
    private TestObservableValues() {
    }

    /// A [ConcurrentObservableValue] that fails the test calling it when an observer throws.
    public static <T> ObservableValue<T> concurrent(T initialValue) {
        return ObservableValue.concurrent(initialValue, failure -> {
            throw new AssertionError("an observer threw", failure);
        });
    }
}
