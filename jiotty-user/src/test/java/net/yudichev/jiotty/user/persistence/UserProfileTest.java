package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-10T18:09:01.323586Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-10T23:30:59.254217Z");

    @Test
    void toStringRedactsEmailAndDisplayNameAndKeepsThePseudonymousId() {
        var profile = new UserProfile("u0164813413255149e4frk",
                                      Optional.of("alexey-testing1@example.com"),
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
    void toStringRendersAbsentEmailAndDisplayNameAsNone() {
        var profile = new UserProfile("u1", Optional.empty(), Optional.empty(), ZoneId.of("UTC"), CREATED_AT, UPDATED_AT);

        assertThat(profile).asString().isEqualTo("UserProfile[id=u1, email=none, displayName=none, timezone=UTC]");
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var profile = new UserProfile("u1",
                                      Optional.of("alexey@example.com"),
                                      Optional.of("Alexey"),
                                      ZoneId.of("UTC"),
                                      CREATED_AT,
                                      UPDATED_AT);

        assertThat(profile.email()).contains("alexey@example.com");
        assertThat(profile.displayName()).contains("Alexey");
    }
}
