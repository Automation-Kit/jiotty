package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class UserProfileInputTest {
    @Test
    void toStringRedactsEmailAndDisplayName() {
        var input = new UserProfileInput("alexey-testing1@example.com", Optional.of("Alexey"), ZoneId.of("Europe/London"));

        assertThat(input).asString()
                         .isEqualTo("UserProfileInput[email=ale…, displayName=Ale…, timezone=Europe/London]")
                         .doesNotContain("alexey-testing1@example.com");
    }

    @Test
    void toStringRendersAnAbsentDisplayNameAsNone() {
        var input = new UserProfileInput("alexey@example.com", Optional.empty(), ZoneId.of("UTC"));

        assertThat(input).asString().isEqualTo("UserProfileInput[email=ale…, displayName=none, timezone=UTC]");
    }

    /// One row per field the record refuses blank, each naming the field its message must carry so a caller can tell which one it rejected.
    static Stream<Arguments> blankFields() {
        return Stream.of(
                // A profile can only be written for an account that has an address, so one arriving without it is a caller defect, not an absence to store.
                arguments("email", (Supplier<UserProfileInput>) () -> new UserProfileInput(" ", Optional.of("Alexey"), ZoneId.of("UTC"))),
                // A blank is not an absence: Optional.of(" ") stores a name that renders as empty everywhere, where Optional.empty() says there is none.
                arguments("displayName", (Supplier<UserProfileInput>) () -> new UserProfileInput("alexey@example.com", Optional.of(" "), ZoneId.of("UTC"))));
    }

    @ParameterizedTest(name = "rejects a blank {0}")
    @MethodSource("blankFields")
    void rejectsABlankField(String field, Supplier<UserProfileInput> construct) {
        assertThatThrownBy(construct::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var input = new UserProfileInput("alexey@example.com", Optional.of("Alexey"), ZoneId.of("UTC"));

        assertThat(input.email()).isEqualTo("alexey@example.com");
        assertThat(input.displayName()).contains("Alexey");
    }
}
