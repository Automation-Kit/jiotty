package net.yudichev.jiotty.energy.octopus.provider;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.inject.Bindings.Rng;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountData;
import net.yudichev.jiotty.connector.octopusenergy.OctopusAccountService;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.energy.octopus.EnergyProviderService;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceFactory;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistry;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceServiceRegistryImpl;
import net.yudichev.jiotty.energy.octopus.PriceForecastServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TestPrices;
import net.yudichev.jiotty.timeseriescache.TimeSeriesCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleSupplier;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OctopusEnergyPriceServiceModuleTest {
    private final List<LifecycleComponent> startedComponents = new ArrayList<>();
    private final ProgrammableClock clock = new ProgrammableClock();
    @Mock
    private OctopusEnergy octopusEnergy;
    @Mock
    private OctopusAccountService accountService;
    @Mock
    private OctopusTariffPriceServiceFactory tariffPriceServiceFactory;
    @Mock
    private PriceForecastServiceRegistry priceForecastRegistry;
    @Mock
    private TimeSeriesCache cache;

    /// Stubs [OctopusAccountService#getAccount()] with a future that never completes, so the poll of [AccountNodeImpl] delivers no account.
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

        startAndSubscribeToAuthState(builder()
                                             .setOctopusAccountId(literally("A-AAAAAAAA"))
                                             .setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx"))
                                             .build());

        verify(accountService).subscribeToAuthState(any());
    }

    @Test
    void accountService_isUsedInsteadOfOpeningAnAccount() {
        startAndSubscribeToAuthState(builder()
                                             .setAccountService(literally(accountService))
                                             .build());

        verify(accountService).subscribeToAuthState(any());
        verifyNoInteractions(octopusEnergy);
    }

    @Test
    void accountPollInterval_setsHowOftenTheAccountIsFetched() {
        Duration interval = Duration.ofSeconds(5);
        when(accountService.getAccount()).thenReturn(completedFuture(OctopusAccountData.builder().build()));
        startAndSubscribeToAuthState(builder()
                                             .setAccountService(literally(accountService))
                                             .withAccountPollInterval(literally(interval))
                                             .build());
        clock.tick();

        clock.advanceTimeAndTick(interval.multipliedBy(2));

        verify(accountService, times(3)).getAccount();
    }

    @Test
    void build_refusesBothAccountSources() {
        OctopusEnergyPriceServiceModule.Builder builder = builder()
                .setOctopusAccountId(literally("A-AAAAAAAA"))
                .setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx"))
                .setAccountService(literally(accountService));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void build_refusesOneCredentialBesideTheAccountService(boolean apiKey) {
        OctopusEnergyPriceServiceModule.Builder builder = withOneCredential(builder(), apiKey)
                .setAccountService(literally(accountService));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void build_refusesOneCredentialAlone(boolean apiKey) {
        OctopusEnergyPriceServiceModule.Builder builder = withOneCredential(builder(), apiKey);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_refusesNoAccountSource() {
        assertThatThrownBy(() -> builder().build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_refusesNoForwardWindow() {
        OctopusEnergyPriceServiceModule.Builder builder = OctopusEnergyPriceServiceModule.builder().setAccountService(literally(accountService));

        assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class).hasMessage("setForwardWindow was not called");
    }

    @Test
    void nonPositiveForwardWindow_failsTheStart() {
        ExposedKeyModule<EnergyProviderService> module = OctopusEnergyPriceServiceModule.builder()
                                                                                        .setAccountService(literally(accountService))
                                                                                        .setForwardWindow(literally(Duration.ZERO))
                                                                                        .build();

        assertThatThrownBy(() -> startAndSubscribeToAuthState(module)).hasRootCauseInstanceOf(IllegalArgumentException.class)
                                                                      .hasRootCauseMessage("forwardWindow must be positive: PT0S");
    }

    private static OctopusEnergyPriceServiceModule.Builder builder() {
        return OctopusEnergyPriceServiceModule.builder().setForwardWindow(literally(TestPrices.FORWARD_WINDOW));
    }

    private static OctopusEnergyPriceServiceModule.Builder withOneCredential(OctopusEnergyPriceServiceModule.Builder builder, boolean apiKey) {
        return apiKey ? builder.setOctopusApiKey(literally("sk_test_xxxxxxxxxxxx")) : builder.setOctopusAccountId(literally("A-AAAAAAAA"));
    }

    private void startAndSubscribeToAuthState(ExposedKeyModule<EnergyProviderService> module) {
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ExecutorFactory.class).toInstance(clock);
                bind(CurrentDateTimeProvider.class).toInstance(clock);
                bind(TaskFailureReporter.class).toInstance(new ListenerBackedTaskExceptionHandlerRegistry());
                bind(DoubleSupplier.class).annotatedWith(Rng.class).toInstance(() -> 0.5);
                bind(OctopusEnergy.class).toInstance(octopusEnergy);
                SchedulingExecutor registryExecutor = clock.createSingleThreadedSchedulingExecutor("registry");
                bind(OctopusTariffPriceServiceRegistry.class).toInstance(new OctopusTariffPriceServiceRegistryImpl(() -> registryExecutor,
                                                                                                                   octopusEnergy,
                                                                                                                   tariffPriceServiceFactory));
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
