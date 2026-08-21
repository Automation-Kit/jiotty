package net.yudichev.jiotty.common.lang;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
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
        Append.to(appendable, buffer -> Append.to(buffer, "formatted"));
        Append.to(appendable, (StringFormattable) null);
        assertThat(appendable.toString()).isEqualTo("cssubctrue42431.52.57nullformattednull");
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

    @ParameterizedTest
    @MethodSource("appendables")
    void iterableWithPrefixSeparatorAndSuffix(Appendable appendable) {
        Append.to(appendable, List.of(1, 2, 3), "<", " | ", ">", (a, object) -> {
            Append.to(a, object);
            Append.to(a, '+');
        });
        Append.to(appendable, List.of(), "<", " | ", ">", Append::to);                       // empty iterable is just prefix+suffix
        Append.to(appendable, List.of("MON", "SAT"), "", ",", "", (a, s) -> Append.to(a, s, 0, 3));  // unbracketed, tight comma
        assertThat(appendable.toString()).isEqualTo("<1+ | 2+ | 3+><>MON,SAT");
    }

    @ParameterizedTest
    @MethodSource("appendables")
    void map(Appendable appendable) {
        Append.to(appendable, ImmutableMap.of(1, "a", 2, "b"),
                  (a, key) -> {
                      Append.to(a, key);
                      Append.to(a, '#');
                  },
                  (a, value) -> {
                      Append.to(a, value);
                      Append.to(a, '+');
                  });
        Append.to(appendable, Map.of());
        Append.to(appendable, ImmutableMap.of(3, "c", 4, "d"));
        assertThat(appendable.toString()).isEqualTo("{1#=a+, 2#=b+}{}{3=c, 4=d}");
    }

    static Stream<Arguments> objectDispatchesOnRuntimeType() {
        return Stream.of(
                arguments("null", null, "null"),
                arguments("formattable", (StringFormattable) buffer -> Append.to(buffer, "fmt"), "fmt"),
                arguments("charSequence", new StringBuilder("cs"), "cs"),
                arguments("string", "str", "str"),
                arguments("character", 'c', "c"),
                arguments("boolean", true, "true"),
                arguments("integer", 42, "42"),
                arguments("long", 43L, "43"),
                arguments("float", 1.5f, "1.5"),
                arguments("double", 2.5, "2.5"),
                arguments("iterable", List.of(1, 2), "[1, 2]"),
                arguments("map", ImmutableMap.of(1, "a"), "{1=a}"),
                // no dedicated overload of its own, so it renders through toString
                arguments("otherType", (byte) 7, "7"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void objectDispatchesOnRuntimeType(String runtimeType, Object value, String expected) {
        var appendable = new StringBuilder();

        Append.to(appendable, value);

        assertThat(appendable.toString()).isEqualTo(expected);
    }

    @Test
    void nestedValuesRenderThroughTheirOwnFormatting() {
        var appendable = new StringBuilder();
        StringFormattable formattable = buffer -> Append.to(buffer, "fmt");

        Append.to(appendable, ImmutableMap.of("k", List.of(formattable, List.of(1, 2))));

        assertThat(appendable.toString()).isEqualTo("{k=[fmt, [1, 2]]}");
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
                // a type with no dedicated overload, so it reaches the Object overload's own append path
                arguments("object", (Consumer<Appendable>) appendable -> Append.to(appendable, (Object) (byte) 7)),
                arguments("formattable", (Consumer<Appendable>) appendable -> Append.to(appendable, buffer -> Append.to(buffer, "x"))));
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

    @Test
    void mapAppendCodeFailure_isWrappedInRuntimeException() {
        assertThatThrownBy(() -> Append.to(new StringBuilder(), Map.of(1, "a"), (_, _) -> {
            throw new Exception("boom");
        }, Append::to))
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
