package net.yudichev.jiotty.common.net;

import net.yudichev.jiotty.common.lang.ThrowingSupplier;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;

import static com.google.common.base.Preconditions.checkNotNull;

public record SslCustomisation(SSLSocketFactory socketFactory, X509TrustManager trustManager) {
    public static SslCustomisation fromTrustStore(TrustStore trustStore) {
        return fromTrustStore(trustStore, () -> null);
    }

    public static SslCustomisation fromKeyAndTrustStore(TrustStore trustStore, TrustStore clientKeyStore) {
        return fromTrustStore(trustStore, () -> {
            // Client keypair + cert chain (PKCS12)
            KeyStore clientKs = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(clientKeyStore.path())) {
                clientKs.load(in, clientKeyStore.password().toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(clientKs, clientKeyStore.password().toCharArray());
            return kmf.getKeyManagers();
        });
    }

    private static SslCustomisation fromTrustStore(TrustStore trustStore, ThrowingSupplier<KeyManager[], Exception> keyManagerSupplier) {
        SSLContext sslContext;
        try {
            KeyStore ts = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(trustStore.path())) {
                ts.load(in, trustStore.password().toCharArray());
            }

            var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);

            sslContext = SSLContext.getInstance("TLS");
            var trustManagers = tmf.getTrustManagers();
            sslContext.init(keyManagerSupplier.get(), trustManagers, null);

            if (trustManagers.length == 1 && trustManagers[0] instanceof X509TrustManager x509TrustManager) {
                return new SslCustomisation(sslContext.getSocketFactory(), x509TrustManager);
            } else {
                throw new IllegalStateException("Unexpected default trust managers:" + Arrays.toString(trustManagers));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed initialising SSL context", e);
        }
    }

    public record TrustStore(Path path, String password) {
        public TrustStore {
            checkNotNull(path);
            checkNotNull(password);
        }
    }
}
