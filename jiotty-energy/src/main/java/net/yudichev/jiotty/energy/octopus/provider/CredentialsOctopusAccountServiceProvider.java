package net.yudichev.jiotty.energy.octopus.provider;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Opens the [OctopusAccountService] of a real Octopus account from its [AccountId] and [ApiKey].
final class CredentialsOctopusAccountServiceProvider implements Provider<OctopusAccountService> {
    private final OctopusEnergy octopusEnergy;
    private final String accountId;
    private final String apiKey;

    @Inject
    public CredentialsOctopusAccountServiceProvider(OctopusEnergy octopusEnergy, @AccountId String accountId, @ApiKey String apiKey) {
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.accountId = checkNotNull(accountId);
        this.apiKey = checkNotNull(apiKey);
    }

    @Override
    public OctopusAccountService get() {
        return octopusEnergy.account(accountId, apiKey);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface ApiKey {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface AccountId {
    }
}
