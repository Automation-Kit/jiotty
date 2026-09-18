package net.yudichev.jiotty.common.security;

import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.AEADBadTagException;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EnvelopeEncryptionTest {
    private static final byte PRIMARY_KEY_ID = 0x42;
    private static final String PRIMARY_ALIAS = "primary";
    private static final String SECONDARY_ALIAS = "secondary";
    private static final String AAD = "context-a";

    @Mock
    private KeyStoreAccess keyStoreAccess;

    private EnvelopeEncryptionImpl encryption;
    private final List<EnvelopeEncryptionImpl> started = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(keyStoreAccess.getSecretKey(PRIMARY_ALIAS)).thenReturn(newAesKey());
        when(keyStoreAccess.getSecretKey(SECONDARY_ALIAS)).thenReturn(newAesKey());
        encryption = startEncryption(PRIMARY_ALIAS, PRIMARY_KEY_ID);
    }

    @AfterEach
    void tearDown() {
        started.forEach(EnvelopeEncryptionImpl::stop);
    }

    @Test
    void encryptProducesEnvelopePrefix() {
        String envelope = encryption.encrypt(AAD, "{\"hello\":\"world\"}");

        assertThat(envelope).startsWith(EnvelopeEncryption.ENVELOPE_PREFIX);
        assertThat(EnvelopeEncryption.isEnvelope(envelope)).isTrue();
    }

    @Test
    void decryptRoundTripsPlaintext() {
        String envelope = encryption.encrypt(AAD, "{\"hello\":\"world\"}");

        assertThat(encryption.decrypt(AAD, envelope)).isEqualTo("{\"hello\":\"world\"}");
    }

    @Test
    void decryptWithMismatchedAadRejected() {
        String envelope = encryption.encrypt(AAD, "payload");

        assertThatThrownBy(() -> encryption.decrypt("context-b", envelope))
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decryptWithDifferentMasterKeyRejected() {
        String envelope = encryption.encrypt(AAD, "payload");
        EnvelopeEncryptionImpl other = startEncryption(SECONDARY_ALIAS, PRIMARY_KEY_ID);

        assertThatThrownBy(() -> other.decrypt(AAD, envelope))
                .hasRootCauseInstanceOf(AEADBadTagException.class);
    }

    @Test
    void decryptRejectsMismatchedKeyId() {
        String envelope = encryption.encrypt(AAD, "payload");
        EnvelopeEncryptionImpl other = startEncryption(PRIMARY_ALIAS, (byte) 0x01);

        assertThatThrownBy(() -> other.decrypt(AAD, envelope))
                .hasMessageContaining("key id");
    }

    @Test
    void isEnvelopeDetectsPlainJson() {
        assertThat(EnvelopeEncryption.isEnvelope("{\"hello\":\"world\"}")).isFalse();
        assertThat(EnvelopeEncryption.isEnvelope("\"plain string\"")).isFalse();
        assertThat(EnvelopeEncryption.isEnvelope("123")).isFalse();
    }

    @Test
    void nonceIsFreshOnEachEncryption() {
        String a = encryption.encrypt(AAD, "payload");
        String b = encryption.encrypt(AAD, "payload");

        assertThat(a).isNotEqualTo(b);
    }

    private EnvelopeEncryptionImpl startEncryption(String alias, byte pinnedKeyId) {
        var enc = new EnvelopeEncryptionImpl(keyStoreAccess, alias) {
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
