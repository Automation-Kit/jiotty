package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheSchemaVersionsTest {
    @Test
    void unannotatedTypeThrows() {
        assertThatThrownBy(() -> CacheSchemaVersions.resolve(TypeToken.of(Unannotated.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must declare a @CacheSchemaVersion");
    }

    @Test
    void annotatedTypeReturnsItsDeclaredVersion() {
        assertThat(CacheSchemaVersions.resolve(TypeToken.of(Versioned.class))).isEqualTo(5);
    }

    @Test
    void versionBelowMinimumThrows() {
        assertThatThrownBy(() -> CacheSchemaVersions.resolve(TypeToken.of(ZeroVersion.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version must be in");
    }

    @Test
    void versionAboveMaximumThrows() {
        assertThatThrownBy(() -> CacheSchemaVersions.resolve(TypeToken.of(TooBigVersion.class)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version must be in");
    }

    private record Unannotated(String value) {}

    @CacheSchemaVersion(5)
    private record Versioned(String value) {}

    @CacheSchemaVersion(0)
    private record ZeroVersion(String value) {}

    @CacheSchemaVersion(70_000)
    private record TooBigVersion(String value) {}
}
