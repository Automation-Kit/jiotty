package net.yudichev.jiotty.energy.octopus;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusEnergyPriceServiceModuleTest {
    private final List<LifecycleComponent> startedComponents = new ArrayList<>();
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusAgilePriceService.Factory agilePriceServiceFactory;
    @Mock
    private PriceForecastServiceRegistry priceForecastRegistry;
    @Mock
    private TimeSeriesCache cache;

    /// Stubs [OctopusAccountService#getAccount()] with a future that never completes, so the account poll started by [OctopusEnergyProviderService]
    /// delivers nothing.
    @BeforeEach
    void setUp() {
        lenient().when(accountService.getAccount()).thenReturn(new CompletableFuture<>());
    }

    @AfterEach
    void tearDown() {
        startedComponents.reversed().forEach(LifecycleComponent::stop);
    }

    @Test
    void credentials_openTheAccountTheyName() {
        when(octopusEnergy.account("A-AAAAAAAA", "sk_test_xxxxxxxxxxxx")).thenReturn(accountService);

        startAndSubscribeToAuthState(OctopusEnergyPriceServiceModule.builder()
                                                                    .setOctopusAccountId(literally("A-AAAAAAAA"))
                                                                    .setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx"))
                                                                    .build());

        verify(accountService).subscribeToAuthState(any());
    }

    @Test
    void accountService_isUsedInsteadOfOpeningAnAccount() {
        startAndSubscribeToAuthState(OctopusEnergyPriceServiceModule.builder()
                                                                    .setAccountService(literally(accountService))
                                                                    .build());

        verify(accountService).subscribeToAuthState(any());
        verifyNoInteractions(octopusEnergy);
    }

    @Test
    void build_refusesBothAccountSources() {
        OctopusEnergyPriceServiceModule.Builder builder = OctopusEnergyPriceServiceModule.builder()
                                                                                         .setOctopusAccountId(literally("A-AAAAAAAA"))
                                                                                         .setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx"))
                                                                                         .setAccountService(literally(accountService));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void build_refusesOneCredentialBesideTheAccountService(boolean apiKey) {
        OctopusEnergyPriceServiceModule.Builder builder = withOneCredential(OctopusEnergyPriceServiceModule.builder(), apiKey)
                .setAccountService(literally(accountService));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void build_refusesOneCredentialAlone(boolean apiKey) {
        OctopusEnergyPriceServiceModule.Builder builder = withOneCredential(OctopusEnergyPriceServiceModule.builder(), apiKey);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_refusesNoAccountSource() {
        assertThatThrownBy(() -> OctopusEnergyPriceServiceModule.builder().build()).isInstanceOf(IllegalArgumentException.class);
    }

    private static OctopusEnergyPriceServiceModule.Builder withOneCredential(OctopusEnergyPriceServiceModule.Builder builder, boolean apiKey) {
        return apiKey ? builder.setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx")) : builder.setOctopusAccountId(literally("A-AAAAAAAA"));
    }

    private void startAndSubscribeToAuthState(ExposedKeyModule<EnergyProviderService> module) {
        Injector injector = Guice.createInjector(ExecutorModule.builder().build(), TimeModule.builder().build(), new AbstractModule() {
            @Override
            protected void configure() {
                bind(OctopusEnergy.class).toInstance(octopusEnergy);
                bind(OctopusAgilePriceServiceRegistry.class).toInstance(new OctopusAgilePriceServiceRegistry(octopusEnergy, agilePriceServiceFactory));
                bind(PriceForecastServiceRegistry.class).toInstance(priceForecastRegistry);
                bind(TimeSeriesCache.class).toInstance(cache);
            }
        }, module);
        List<Binding<LifecycleComponent>> components = injector.findBindingsByType(new TypeLiteral<>() {});
        assertThat(components).isNotEmpty();
        for (Binding<LifecycleComponent> binding : components) {
            LifecycleComponent component = binding.getProvider().get();
            component.start();
            startedComponents.add(component);
        }
        injector.getInstance(module.getExposedKey()).subscribeToAuthState(_ -> {});
    }
}
