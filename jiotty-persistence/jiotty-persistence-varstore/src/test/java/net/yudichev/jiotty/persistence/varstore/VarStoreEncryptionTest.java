package net.yudichev.jiotty.persistence.varstore;

import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VarStoreEncryptionTest {
    private static final byte PRIMARY_KEY_ID = 0x42;
    private static final String PRIMARY_ALIAS = "primary";
    private static final String SECONDARY_ALIAS = "secondary";

    @Mock
    private KeyStoreAccess keyStoreAccess;

    private VarStoreEncryptionImpl encryption;
    private final List<VarStoreEncryptionImpl> started = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(keyStoreAccess.getSecretKey(PRIMARY_ALIAS)).thenReturn(newAesKey());
        when(keyStoreAccess.getSecretKey(SECONDARY_ALIAS)).thenReturn(newAesKey());
        encryption = startEncryption(PRIMARY_ALIAS, PRIMARY_KEY_ID);
    }

    @AfterEach
    void tearDown() {
        started.forEach(VarStoreEncryptionImpl::stop);
    }

    @Test
    void encryptProducesEnvelopePrefix() {
        String envelope = encryption.encrypt("alice", "token", "{\"hello\":\"world\"}");

        assertThat(envelope).startsWith(VarStoreEncryption.ENVELOPE_PREFIX);
        assertThat(VarStoreEncryption.isEnvelope(envelope)).isTrue();
    }

    @Test
    void decryptRoundTripsPlaintext() {
        String envelope = encryption.encrypt("alice", "token", "{\"hello\":\"world\"}");

        assertThat(encryption.decrypt("alice", "token", envelope)).isEqualTo("{\"hello\":\"world\"}");
    }

    @ParameterizedTest
    @MethodSource
    void decryptWithMismatchedAadRejected(String encryptUserId, String encryptKey, String decryptUserId, String decryptKey) {
        String envelope = encryption.encrypt(encryptUserId, encryptKey, "payload");

        assertThatThrownBy(() -> encryption.decrypt(decryptUserId, decryptKey, envelope))
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    static Stream<Arguments> decryptWithMismatchedAadRejected() {
        return Stream.of(
                // userId differs
                arguments("alice", "token", "bob", "token"),
                // key differs
                arguments("alice", "tokenA", "alice", "tokenB"));
    }

    @Test
    void decryptWithDifferentMasterKeyRejected() {
        String envelope = encryption.encrypt("alice", "token", "payload");
        VarStoreEncryptionImpl other = startEncryption(SECONDARY_ALIAS, PRIMARY_KEY_ID);

        assertThatThrownBy(() -> other.decrypt("alice", "token", envelope))
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decryptRejectsMismatchedKeyId() {
        String envelope = encryption.encrypt("alice", "token", "payload");
        VarStoreEncryptionImpl other = startEncryption(PRIMARY_ALIAS, (byte) 0x01);

        assertThatThrownBy(() -> other.decrypt("alice", "token", envelope))
                .hasMessageContaining("key id");
    }

    @Test
    void isEnvelopeDetectsPlainJson() {
        assertThat(VarStoreEncryption.isEnvelope("{\"hello\":\"world\"}")).isFalse();
        assertThat(VarStoreEncryption.isEnvelope("\"plain string\"")).isFalse();
        assertThat(VarStoreEncryption.isEnvelope("123")).isFalse();
    }

    @Test
    void nonceIsFreshOnEachEncryption() {
        String a = encryption.encrypt("alice", "token", "payload");
        String b = encryption.encrypt("alice", "token", "payload");

        assertThat(a).isNotEqualTo(b);
    }

    private VarStoreEncryptionImpl startEncryption(String alias, byte pinnedKeyId) {
        var enc = new VarStoreEncryptionImpl(keyStoreAccess, alias) {
            @Override
            byte computeKeyId(String aliasArg) {
                return pinnedKeyId;
            }
        };
        enc.start();
        started.add(enc);
        return enc;
    }

    private static SecretKey newAesKey() {
        return getAsUnchecked(() -> {
            var gen = KeyGenerator.getInstance("AES");
            gen.init(256);
            return gen.generateKey();
        });
    }
}
