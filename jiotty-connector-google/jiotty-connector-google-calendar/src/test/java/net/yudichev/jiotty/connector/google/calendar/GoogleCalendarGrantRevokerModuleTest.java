package net.yudichev.jiotty.connector.google.calendar;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.security.StoredGrantRevoker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarModule.BASE_API_NAME;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarModule.SCOPE;
import static net.yudichev.jiotty.security.OAuth2TokenManager.storedTokenKey;
import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarGrantRevokerModuleTest {
    private static final String CLIENT_ID = "123-abc.apps.googleusercontent.com";
    private static final String UNREADABLE_TOKEN = "not-an-encrypted-token";
    private final ProgrammableClock clock = new ProgrammableClock();
    private final List<LifecycleComponent> startedComponents = new ArrayList<>();

    @AfterEach
    void tearDown() {
        startedComponents.reversed().forEach(LifecycleComponent::stop);
    }

    @Test
    void exposesARevokerUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<StoredGrantRevoker> module = GoogleCalendarGrantRevokerModule.builder().withAnnotation(forAnnotation(annotation)).build();

        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(StoredGrantRevoker.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isNotNull();
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    /// An unreadable row is deleted without a request to Google, so the rows a revocation deletes are the rows it matched.
    @Test
    void matchesTheTokensOfTheRequestedScopes() {
        ExposedKeyModule<StoredGrantRevoker> module = GoogleCalendarGrantRevokerModule.builder().build();
        var injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(ExecutorFactory.class).toInstance(clock);
                bind(CurrentDateTimeProvider.class).toInstance(clock);
            }
        }, module);
        injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {}).forEach(binding -> {
            LifecycleComponent component = binding.getProvider().get();
            component.start();
            startedComponents.add(component);
        });
        var varStore = new InMemoryVarStore();
        String otherScopeKey = storedTokenKey(BASE_API_NAME, CLIENT_ID, GoogleCalendarScopes.EVENTS_READONLY);
        varStore.saveValue(storedTokenKey(BASE_API_NAME, CLIENT_ID, SCOPE), UNREADABLE_TOKEN);
        varStore.saveValue(otherScopeKey, UNREADABLE_TOKEN);

        CompletableFuture<StoredGrantRevoker.Result> revocation = injector.getInstance(module.getExposedKey()).revoke(varStore);
        clock.tick();

        assertThat(revocation).failsWithin(Duration.ZERO);
        assertThat(varStore.keysStartingWith("")).containsExactly(otherScopeKey);
    }
}
