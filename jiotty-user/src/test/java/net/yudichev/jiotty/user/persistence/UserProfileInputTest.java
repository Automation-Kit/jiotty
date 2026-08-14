package net.yudichev.jiotty.user.persistence;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileInputTest {
    @Test
    void toStringRedactsEmailAndDisplayName() {
        var input = new UserProfileInput(Optional.of("alexey-testing1@example.com"), Optional.of("Alexey"), ZoneId.of("Europe/London"));

        assertThat(input).asString()
                         .isEqualTo("UserProfileInput[email=ale…, displayName=Ale…, timezone=Europe/London]")
                         .doesNotContain("alexey-testing1@example.com");
    }

    @Test
    void toStringRendersAbsentEmailAndDisplayNameAsNone() {
        var input = new UserProfileInput(Optional.empty(), Optional.empty(), ZoneId.of("UTC"));

        assertThat(input).asString().isEqualTo("UserProfileInput[email=none, displayName=none, timezone=UTC]");
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var input = new UserProfileInput(Optional.of("alexey@example.com"), Optional.of("Alexey"), ZoneId.of("UTC"));

        assertThat(input.email()).contains("alexey@example.com");
        assertThat(input.displayName()).contains("Alexey");
    }
}
