package net.yudichev.jiotty.common.security;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// AES-256-GCM with a per-value random 12-byte nonce, under a master key read from the key store at start.
///
/// The envelope layout is a persisted format — every value already written is in it — so it is recorded here rather than inferred from the code below:
/// base64 after the [EnvelopeEncryption#ENVELOPE_PREFIX] sigil, carrying
/// `magic(3B="ENC") | ver(1B=0x01) | keyId(1B) | nonceLen(1B=12) | nonce(12B) | ciphertext||tag`.
public class EnvelopeEncryptionImpl extends BaseLifecycleComponent implements EnvelopeEncryption {
    private static final byte[] MAGIC = {'E', 'N', 'C'};
    private static final byte VERSION = 0x01;
    private static final int NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final KeyStoreAccess keyStoreAccess;
    private final String alias;
    private final SecureRandom rng = new SecureRandom();

    private SecretKey masterKey;
    private byte keyId;

    @Inject
    public EnvelopeEncryptionImpl(@Dependency KeyStoreAccess keyStoreAccess,
                                  @MasterKeyAlias String alias) {
        this.keyStoreAccess = checkNotNull(keyStoreAccess);
        this.alias = checkNotNull(alias, "alias");
    }

    @Override
    protected void doStart() {
        masterKey = keyStoreAccess.getSecretKey(alias);
        keyId = computeKeyId(alias);
    }

    @Override
    public String encrypt(String associatedData, String plaintext) {
        var nonce = new byte[NONCE_LEN];
        rng.nextBytes(nonce);
        byte[] ciphertext;
        try {
            var cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                 | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException("Failed to encrypt value", e);
        }

        var buffer = ByteBuffer.allocate(MAGIC.length + 3 + nonce.length + ciphertext.length);
        buffer.put(MAGIC);
        buffer.put(VERSION);
        buffer.put(keyId);
        buffer.put((byte) nonce.length);
        buffer.put(nonce);
        buffer.put(ciphertext);

        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(buffer.array());
    }

    @Override
    public String decrypt(String associatedData, String envelope) {
        checkArgument(EnvelopeEncryption.isEnvelope(envelope), "Not an encryption envelope");
        byte[] bytes = Base64.getDecoder().decode(envelope.substring(ENVELOPE_PREFIX.length()));
        var buffer = ByteBuffer.wrap(bytes);

        var magic = new byte[MAGIC.length];
        buffer.get(magic);
        checkArgument(Arrays.equals(magic, MAGIC), "Envelope magic mismatch");
        byte version = buffer.get();
        checkArgument(version == VERSION, "Unsupported envelope version %s", version);
        byte storedKeyId = buffer.get();
        checkArgument(storedKeyId == keyId,
                      "Envelope key id %s does not match current key id %s — master key may have rotated",
                      storedKeyId, keyId);
        byte nonceLen = buffer.get();
        checkArgument(nonceLen == NONCE_LEN, "Unexpected nonce length %s", nonceLen);
        var nonce = new byte[nonceLen];
        buffer.get(nonce);
        var ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            var cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException
                 | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException("Failed to decrypt value", e);
        }
    }

    /// Package-private test seam: overriding lets a test pin a deterministic key id without reverse-engineering the SHA-256 digest of the alias. Production
    /// callers must not rely on subclassing.
    byte computeKeyId(String alias) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(alias.getBytes(StandardCharsets.UTF_8))[0];
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface MasterKeyAlias {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }
}
