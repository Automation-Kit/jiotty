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
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileVarStoreTest {
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

    @Test
    void readValueEncryptedThrowsWhenEncryptionNotConfigured(@TempDir Path tempDir) {
        VarStore varStore = new FileVarStore(tempDir.resolve("data.json"), true, Optional.empty());

        assertThatThrownBy(() -> varStore.readValueEncrypted(String.class, "any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured with encryption");
        verifyNoInteractions(encryption);
    }
}
