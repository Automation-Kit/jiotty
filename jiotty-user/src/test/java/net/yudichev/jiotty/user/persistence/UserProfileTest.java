package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class UserProfileTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-10T18:09:01.323586Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-10T23:30:59.254217Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void toStringRedactsEmailAndDisplayNameAndKeepsThePseudonymousId() {
        var profile = new UserProfile("u0164813413255149e4frk",
                                      "alexey-testing1@example.com",
                                      Optional.of("Alexey"),
                                      ZoneId.of("Europe/London"),
                                      CREATED_AT,
                                      UPDATED_AT);

        assertThat(profile).asString()
                           .isEqualTo("UserProfile[id=u0164813413255149e4frk, email=ale…, displayName=Ale…, timezone=Europe/London]")
                           .doesNotContain("alexey-testing1@example.com")
                           .doesNotContain("Alexey,");
    }

    @Test
    void toStringRendersAnAbsentDisplayNameAsNone() {
        var profile = new UserProfile("u1", "alexey@example.com", Optional.empty(), ZoneId.of("UTC"), CREATED_AT, UPDATED_AT);

        assertThat(profile).asString().isEqualTo("UserProfile[id=u1, email=ale…, displayName=none, timezone=UTC]");
    }

    /// One row per field the record refuses blank, each naming the field its message must carry so a caller can tell which one it rejected.
    static Stream<Arguments> blankFields() {
        return Stream.of(
                arguments("id", (Supplier<UserProfile>) () -> new UserProfile(" ", "alexey@example.com", Optional.of("Alexey"), UTC, CREATED_AT, UPDATED_AT)),
                // Every account has an address, so the record rejects one rather than modelling an absence the rest of the system would answer for.
                arguments("email", (Supplier<UserProfile>) () -> new UserProfile("u1", " ", Optional.of("Alexey"), UTC, CREATED_AT, UPDATED_AT)),
                // A blank is not an absence: Optional.of(" ") stores a name that renders as empty everywhere, where Optional.empty() says there is none.
                arguments("displayName", (Supplier<UserProfile>) () -> new UserProfile("u1", "alexey@example.com", Optional.of(" "), UTC, CREATED_AT,
                                                                                       UPDATED_AT)));
    }

    @ParameterizedTest(name = "rejects a blank {0}")
    @MethodSource("blankFields")
    void rejectsABlankField(String field, Supplier<UserProfile> construct) {
        assertThatThrownBy(construct::get).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var profile = new UserProfile("u1",
                                      "alexey@example.com",
                                      Optional.of("Alexey"),
                                      ZoneId.of("UTC"),
                                      CREATED_AT,
                                      UPDATED_AT);

        assertThat(profile.email()).isEqualTo("alexey@example.com");
        assertThat(profile.displayName()).contains("Alexey");
    }
}
