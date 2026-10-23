package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CredentialsOctopusAccountServiceProviderTest {
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;

    @Test
    void get_opensTheHandleOfTheAccountTheCredentialsName() {
        when(octopusEnergy.account("A-AAAAAAAA", "sk_test_xxxxxxxxxxxx")).thenReturn(accountService);

        var provider = new CredentialsOctopusAccountServiceProvider(octopusEnergy, "A-AAAAAAAA", "sk_test_xxxxxxxxxxxx");

        assertThat(provider.get()).isSameAs(accountService);
    }
}
