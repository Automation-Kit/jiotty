package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class StreamDefinitionsTest {
    private static final String STREAM_A = "stream-a";
    private static final String STREAM_B = "stream-b";
    private static final Scope USER_1 = Scope.user("user-1");
    private static final Scope USER_2 = Scope.user("user-2");
    private static final TypeToken<Integer> TYPE = TypeToken.of(Integer.class);

    private final StreamDefinitions definitions = new StreamDefinitions();

    @Test
    void register_sameDefinitionTwice_isAccepted() {
        definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE);

        assertThatCode(() -> definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE)).doesNotThrowAnyException();
    }

    @Test
    void register_sameStreamInAnotherScope_isIndependent() {
        definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE);

        assertThatCode(() -> definitions.register(STREAM_A, USER_2, Resolution.halfHourly(), TYPE)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource
    void register_conflictingRedefinition_throws(Resolution resolution, TypeToken<?> type) {
        definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE);

        assertThatThrownBy(() -> definitions.register(STREAM_A, USER_1, resolution, type))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conflicting redefinition");
    }

    static Stream<Arguments> register_conflictingRedefinition_throws() {
        return Stream.of(arguments(Resolution.halfHourly(), TYPE),
                         arguments(Resolution.daily(), TypeToken.of(String.class)));
    }

    @Test
    void forgetScope_forgetsOnlyThatScopesDefinitions() {
        definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE);
        definitions.register(STREAM_A, USER_2, Resolution.daily(), TYPE);

        definitions.forgetScope(USER_1);

        assertThatCode(() -> definitions.register(STREAM_A, USER_1, Resolution.halfHourly(), TYPE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> definitions.register(STREAM_A, USER_2, Resolution.halfHourly(), TYPE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forgetStream_forgetsOnlyThatStreamsDefinitions() {
        definitions.register(STREAM_A, USER_1, Resolution.daily(), TYPE);
        definitions.register(STREAM_B, USER_1, Resolution.daily(), TYPE);

        definitions.forgetStream(STREAM_A);

        assertThatCode(() -> definitions.register(STREAM_A, USER_1, Resolution.halfHourly(), TYPE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> definitions.register(STREAM_B, USER_1, Resolution.halfHourly(), TYPE)).isInstanceOf(IllegalArgumentException.class);
    }
}
