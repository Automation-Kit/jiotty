package net.yudichev.jiotty.common.lang;

import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class CompositeException extends RuntimeException {
    private CompositeException(Collection<RuntimeException> exceptions) {
        super(exceptions.stream()
                        .map(Throwable::getMessage)
                        .collect(Collectors.joining("; ")));
    }

    public static <T> void runForAll(Iterable<? extends T> items, Consumer<? super T> consumer) {
        // Most runs end with no exception, and `items` does not report its size, so the list starts at the default capacity.
        var exceptions = new ArrayList<RuntimeException>();
        for (T item : items) {
            try {
                consumer.accept(item);
            } catch (RuntimeException e) {
                exceptions.add(e);
            }
        }
        switch (exceptions.size()) {
            case 0 -> {
            }
            case 1 -> throw exceptions.getFirst();
            default -> throw new CompositeException(exceptions);
        }
    }
}
