package net.yudichev.jiotty.common.net;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.net.SslCustomisation.TrustStore;
import static net.yudichev.jiotty.common.net.SslCustomisation.fromKeyAndTrustStore;
import static net.yudichev.jiotty.common.net.SslCustomisation.fromTrustStore;

final class SslCustomisationProvider implements Provider<SslCustomisation> {

    private final TrustStore certTrustStore;
    private final @Nullable TrustStore clientKeyStore;

    @Inject
    public SslCustomisationProvider(@CertTrustStore TrustStore certTrustStore,
                                    @ClientKeyStore Optional<TrustStore> clientKeyStore) {
        this.certTrustStore = checkNotNull(certTrustStore);
        this.clientKeyStore = clientKeyStore.orElse(null);
    }

    @Override
    public SslCustomisation get() {
        return getAsUnchecked(() -> {
            if (clientKeyStore == null) {
                return fromTrustStore(certTrustStore);
            }
            return fromKeyAndTrustStore(certTrustStore, clientKeyStore);
        });
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface CertTrustStore {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientKeyStore {
    }
}
