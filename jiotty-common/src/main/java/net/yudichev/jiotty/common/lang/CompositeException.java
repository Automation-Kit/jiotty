package net.yudichev.jiotty.common.lang;

import com.google.common.collect.ImmutableList;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.google.common.collect.ImmutableList.builder;

public final class CompositeException extends RuntimeException {
    private CompositeException(Collection<RuntimeException> exceptions) {
        super(exceptions.stream()
                        .map(Throwable::getMessage)
                        .collect(Collectors.joining("; ")));
    }

    public static <T> void runForAll(Iterable<? extends T> items, Consumer<? super T> consumer) {
        ImmutableList.Builder<RuntimeException> exceptionListBuilder = builder();
        for (T item : items) {
            try {
                consumer.accept(item);
            } catch (RuntimeException e) {
                exceptionListBuilder.add(e);
            }
        }
        List<RuntimeException> exceptions = exceptionListBuilder.build();
        switch (exceptions.size()) {
            case 0 -> {
            }
            case 1 -> throw exceptions.getFirst();
            default -> throw new CompositeException(exceptions);
        }
    }
}
