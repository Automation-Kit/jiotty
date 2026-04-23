package net.yudichev.jiotty.common.keystore;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;

import javax.crypto.SecretKey;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class KeyStoreAccessImpl implements KeyStoreAccess {
    private final Path pathToKeystore;

    private KeyStore ks;
    private KeyStore.PasswordProtection pp;

    @Inject
    public KeyStoreAccessImpl(@PathToKeystore Path pathToKeystore,
                              @KeyStorePass String keystorePass,
                              @KeyStoreType String keystoreType) {
        this.pathToKeystore = pathToKeystore;
        char[] keystorePassCharArray = keystorePass.toCharArray();
        asUnchecked(() -> {
            ks = KeyStore.getInstance(keystoreType);
            try (InputStream in = Files.newInputStream(pathToKeystore)) {
                ks.load(in, keystorePassCharArray);
            }
            // All entries use the same password as the keystore here:
            pp = new KeyStore.PasswordProtection(keystorePassCharArray);
        });
    }

    @Override
    public String getEntry(String alias) {
        return getAsUnchecked(() -> {
            byte[] encoded = getSecretKeyEntry(alias).getSecretKey().getEncoded();
            checkState(encoded != null, "KeyStore '%s' entry for alias '%s' has no encoded secret key", pathToKeystore, alias);
            return new String(encoded, UTF_8);
        });
    }

    @Override
    public SecretKey getSecretKey(String alias) {
        return getAsUnchecked(() -> getSecretKeyEntry(alias).getSecretKey());
    }

    private KeyStore.SecretKeyEntry getSecretKeyEntry(String alias)
            throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableEntryException {
        checkArgument(alias != null, "alias must not be null");
        var entry = ks.getEntry(alias, pp);
        checkArgument(entry != null, "KeyStore '%s' has no entry for alias '%s'", pathToKeystore, alias);
        checkState(entry instanceof KeyStore.SecretKeyEntry,
                   "KeyStore '%s' entry for alias '%s' is %s, expected SecretKeyEntry",
                   pathToKeystore, alias, entry.getClass().getName());
        return (KeyStore.SecretKeyEntry) entry;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface PathToKeystore {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface KeyStorePass {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface KeyStoreType {
    }
}
