package net.yudichev.jiotty.persistence.varstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static java.nio.file.Files.writeString;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileVarStoreTest {
    private static final Instant WHEN = Instant.parse("2026-08-27T12:00:00Z");
    private static final String ISO_JSON = "\"2026-08-27T12:00:00Z\"";

    @Mock
    private VarStoreEncryption encryption;

    @Test
    void encryptedReadDecryptsEnvelope(@TempDir Path tempDir) {
        when(encryption.encrypt("", "token", "\"super-secret\"")).thenReturn("ENC1$cipher");
        when(encryption.decrypt("", "token", "ENC1$cipher")).thenReturn("\"super-secret\"");
        VarStore varStore = new FileVarStore(tempDir.resolve("data.json"), true, Optional.of(encryption));

        varStore.saveValueEncrypted("token", "super-secret");

        assertThat(varStore.readValueEncrypted(String.class, "token")).contains("super-secret");
    }

    @Test
    void encryptedReadOfAbsentKeyIsEmpty(@TempDir Path tempDir) {
        VarStore varStore = new FileVarStore(tempDir.resolve("data.json"), true, Optional.of(encryption));

        assertThat(varStore.readValueEncrypted(String.class, "absent")).isEmpty();
        verifyNoInteractions(encryption);
    }

    @ParameterizedTest
    @MethodSource
    void encryptedReadRejectsNonEnvelopeValue(Object storedValue, Class<?> type, @TempDir Path tempDir) {
        VarStore varStore = new FileVarStore(tempDir.resolve("data.json"), true, Optional.of(encryption));

        varStore.saveValue("k", storedValue);

        assertThatThrownBy(() -> varStore.readValueEncrypted(type, "k"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not an encryption envelope");
        verifyNoInteractions(encryption);
    }

    static Stream<Arguments> encryptedReadRejectsNonEnvelopeValue() {
        return Stream.of(arguments("plaintext", String.class),   // textual, but not an ENC1$ envelope
                         arguments(42, Integer.class));           // not even a textual node
    }

    /// A store file holding the numeric form is on disk, so the read path has to load it.
    @Test
    void readsAValueHoldingATemporalValueInTheLegacyNumericForm(@TempDir Path tempDir) {
        VarStore varStore = storeSeededWithLegacyInstant(tempDir);

        assertThat(varStore.readValue(Instant.class, "since")).contains(WHEN);
    }

    /// What a data subject reads in the Art. 15 archive, which reports the stored form verbatim.
    @Test
    void writesATemporalValueAsAnIsoString(@TempDir Path tempDir) {
        Path storeFile = tempDir.resolve("data.json");
        VarStore varStore = new FileVarStore(storeFile, true, Optional.empty());

        varStore.saveValue("since", WHEN);

        assertThat(varStore.exportEntries()).containsExactly(new VarStore.ExportedEntry("since", false, ISO_JSON));
    }

    private static VarStore storeSeededWithLegacyInstant(Path tempDir) {
        Path storeFile = tempDir.resolve("data.json");
        asUnchecked(() -> writeString(storeFile, "{\"since\":1787832000.000000000}"));
        return new FileVarStore(storeFile, true, Optional.empty());
    }

    @Test
    void readValueEncryptedThrowsWhenEncryptionNotConfigured(@TempDir Path tempDir) {
        VarStore varStore = new FileVarStore(tempDir.resolve("data.json"), true, Optional.empty());

        assertThatThrownBy(() -> varStore.readValueEncrypted(String.class, "any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured with encryption");
        verifyNoInteractions(encryption);
    }
}
