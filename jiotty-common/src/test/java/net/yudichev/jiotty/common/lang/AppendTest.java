package net.yudichev.jiotty.common.lang;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AppendTest {
    static List<Appendable> appendables() {
        return List.of(new StringBuilder(), new StringWriter());
    }

    @ParameterizedTest
    @MethodSource("appendables")
    void scalars(Appendable appendable) {
        Append.to(appendable, "cs");
        Append.to(appendable, "-sub-", 1, 4);
        Append.to(appendable, 'c');
        Append.to(appendable, true);
        Append.to(appendable, 42);
        Append.to(appendable, 43L);
        Append.to(appendable, 1.5f);
        Append.to(appendable, 2.5);
        Append.to(appendable, (Object) 7);
        Append.to(appendable, (Object) null);
        Append.to(appendable, buffer -> buffer.append("formatted"));
        assertThat(appendable.toString()).isEqualTo("cssubctrue42431.52.57nullformatted");
    }

    @ParameterizedTest
    @MethodSource("appendables")
    void iterable(Appendable appendable) {
        Append.to(appendable, List.of(1, 2, 3), (a, object) -> {
            Append.to(a, object);
            Append.to(a, '+');
        });
        Append.to(appendable, List.of());
        Append.to(appendable, List.of(4, 5));
        assertThat(appendable.toString()).isEqualTo("[1+, 2+, 3+][][4, 5]");
    }

    static Stream<Arguments> appendableFailure_isWrappedInRuntimeException() {
        return Stream.of(
                arguments("charSequence", (Consumer<Appendable>) appendable -> Append.to(appendable, "x")),
                arguments("charSequenceRange", (Consumer<Appendable>) appendable -> Append.to(appendable, "xyz", 0, 1)),
                arguments("char", (Consumer<Appendable>) appendable -> Append.to(appendable, 'x')),
                arguments("boolean", (Consumer<Appendable>) appendable -> Append.to(appendable, true)),
                arguments("int", (Consumer<Appendable>) appendable -> Append.to(appendable, 1)),
                arguments("long", (Consumer<Appendable>) appendable -> Append.to(appendable, 1L)),
                arguments("float", (Consumer<Appendable>) appendable -> Append.to(appendable, 1.5f)),
                arguments("double", (Consumer<Appendable>) appendable -> Append.to(appendable, 1.5)),
                arguments("object", (Consumer<Appendable>) appendable -> Append.to(appendable, (Object) "x")),
                arguments("formattable", (Consumer<Appendable>) appendable -> Append.to(appendable, buffer -> buffer.append("x"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void appendableFailure_isWrappedInRuntimeException(String overload, Consumer<Appendable> appendAction) {
        assertThatThrownBy(() -> appendAction.accept(new ThrowingAppendable()))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("boom");
    }

    @Test
    void iterableAppendCodeFailure_isWrappedInRuntimeException() {
        assertThatThrownBy(() -> Append.to(new StringBuilder(), List.of(1), (_, _) -> {
            throw new Exception("boom");
        }))
                .isInstanceOf(RuntimeException.class)
                .cause().isInstanceOf(Exception.class).hasMessage("boom");
    }

    private static final class ThrowingAppendable implements Appendable {
        @Override
        public Appendable append(CharSequence csq) throws IOException {
            throw new IOException("boom");
        }

        @Override
        public Appendable append(CharSequence csq, int start, int end) throws IOException {
            throw new IOException("boom");
        }

        @Override
        public Appendable append(char c) throws IOException {
            throw new IOException("boom");
        }
    }
}
