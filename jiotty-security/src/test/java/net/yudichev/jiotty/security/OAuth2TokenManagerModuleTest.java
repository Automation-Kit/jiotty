package net.yudichev.jiotty.security;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class OAuth2TokenManagerModuleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configures(boolean withLoginUrl) {
        OAuth2TokenManagerModule.Builder builder = OAuth2TokenManagerModule.builder()
                                                                           .setClientId(literally("cid"))
                                                                           .withClientSecret(literally("cs"))
                                                                           .setApiName(literally("api"))
                                                                           .setTokenUrl(literally("http://token"))
                                                                           .setScope(literally("scope"));
        if (withLoginUrl) {
            builder.withLoginUrl(literally("http://login"))
                   .withLoginExtraParams(literally(Map.of("access_type", "offline")));
        }
        var module = builder.build();
        Guice.createInjector(new TimeModule(),
                             new ExecutorModule(),
                             new AbstractModule() {
                                 @Override
                                 protected void configure() {
                                     bind(VarStore.class).toInstance(new InMemoryVarStore());
                                 }
                             },
                             module)
             .getBinding(OAuth2TokenManager.class);
    }

    @Test
    void exposesUnderTheSpecifiedAnnotation() {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<OAuth2TokenManager> module = OAuth2TokenManagerModule.builder()
                                                                              .setClientId(literally("cid"))
                                                                              .setApiName(literally("api"))
                                                                              .setTokenUrl(literally("http://token"))
                                                                              .setScope(literally("scope"))
                                                                              .withAnnotation(forAnnotation(annotation))
                                                                              .build();
        var injector = Guice.createInjector(new TimeModule(),
                                            new ExecutorModule(),
                                            new AbstractModule() {
                                                @Override
                                                protected void configure() {
                                                    bind(VarStore.class).toInstance(new InMemoryVarStore());
                                                }
                                            },
                                            module);
        assertThat(module.getExposedKey()).isEqualTo(Key.get(OAuth2TokenManager.class, annotation));
        assertThat(injector.getExistingBinding(Key.get(OAuth2TokenManager.class))).isNull();
    }


    /// The executor thread name carries the subject id so concurrent per-user managers stay distinguishable in a shared log and in the executor metrics.
    @ParameterizedTest
    @CsvSource({"user-1, TeslaFleet-user-1-oauth2",
            "'',     TeslaFleet-oauth2"})
    void executorThreadNameCarriesTheSubjectId(String logSubjectId, String expectedThreadName) {
        assertThat(OAuth2TokenManagerModule.executorThreadName("TeslaFleet", logSubjectId)).isEqualTo(expectedThreadName);
    }

    /// The persisted token is keyed by the API name, so a per-user discriminator must never reach it: folding a subject id into the API name silently
    /// repoints every stored token at a key that holds nothing, and each user is left waiting to re-authenticate with no error raised anywhere.
    @Test
    void storedTokenSurvivesRegardlessOfTheSubjectId() {
        var varStore = new InMemoryVarStore();
        var token = OauthAccessToken.of("stored-at", "stored-rt", Instant.now().plusSeconds(1800));
        varStore.saveValueEncrypted("apiOauth2Token_cid_scope", token);

        // Two managers for the same API differing only by subject id must both find the token the other stored.
        for (String subjectId : new String[]{"", "user-1", "user-2"}) {
            ExposedKeyModule<OAuth2TokenManager> module = OAuth2TokenManagerModule.builder()
                                                                                  .setClientId(literally("cid"))
                                                                                  .setApiName(literally("api"))
                                                                                  .setTokenUrl(literally("http://token"))
                                                                                  .setScope(literally("scope"))
                                                                                  .withLogSubjectId(literally(subjectId))
                                                                                  .withVarStore(literally(varStore))
                                                                                  .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                                  .build();
            var injector = Guice.createInjector(new TimeModule(), new ExecutorModule(), module);
            List<LifecycleComponent> components = injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                                                          .stream()
                                                          .map(binding -> injector.getInstance(binding.getKey()))
                                                          .toList();
            components.forEach(LifecycleComponent::start);
            try {
                // Reaching Success means the manager found and loaded the persisted token; a subject id folded into the API name would key it elsewhere and
                // leave the manager waiting for a login that never comes.
                OAuth2TokenManager manager = injector.getInstance(module.getExposedKey());
                // The image is delivered on the manager's executor, so signal across threads rather than reading a list the executor has yet to fill.
                var authenticated = new CompletableFuture<AuthState.Success>();
                try (var _ = manager.subscribeToAccessTokenState(state -> {
                    if (state instanceof AuthState.Success success) {
                        authenticated.complete(success);
                    }
                })) {
                    assertThat(authenticated).as("subject id '%s'", subjectId)
                                             .succeedsWithin(Duration.ofSeconds(5))
                                             .satisfies(success -> assertThat(success.authInfo()).isEqualTo("stored-at"));
                }
            } finally {
                components.reversed().forEach(LifecycleComponent::stop);
            }
        }
    }

    /// A login pending at construction means the owner supplies an auth code as part of its own startup, so a start with no stored token must present as a
    /// transient in-progress state rather than the permanent not-authenticated failure that reads as rejected credentials.
    @Test
    void loginPendingStartWithNoStoredToken_publishesTransientAwaitingLoginState() {
        ExposedKeyModule<OAuth2TokenManager> module = OAuth2TokenManagerModule.builder()
                                                                              .setClientId(literally("cid"))
                                                                              .setApiName(literally("api"))
                                                                              .setTokenUrl(literally("http://token"))
                                                                              .setScope(literally("scope"))
                                                                              .withVarStore(literally(new InMemoryVarStore()))
                                                                              .withLoginPending(literally(true))
                                                                              .withAnnotation(forAnnotation(uniqueAnnotation()))
                                                                              .build();
        var injector = Guice.createInjector(new TimeModule(), new ExecutorModule(), module);
        List<LifecycleComponent> components = injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})
                                                      .stream()
                                                      .map(binding -> injector.getInstance(binding.getKey()))
                                                      .toList();
        components.forEach(LifecycleComponent::start);
        try {
            OAuth2TokenManager manager = injector.getInstance(module.getExposedKey());
            // The image is delivered on the manager's executor, so signal across threads rather than reading state the executor has yet to publish.
            var observedAuthState = new CompletableFuture<AuthState>();
            try (var _ = manager.subscribeToAccessTokenState(observedAuthState::complete)) {
                assertThat(observedAuthState).succeedsWithin(Duration.ofSeconds(5))
                                             .isInstanceOfSatisfying(AuthState.TransientFailure.class,
                                                                     failure -> assertThat(failure.description()).isEqualTo("awaiting login"));
            }
        } finally {
            components.reversed().forEach(LifecycleComponent::stop);
        }
    }

    @Test
    void configuresPublicClientWithoutSecret() {
        var module = OAuth2TokenManagerModule.builder()
                                             .setClientId(literally("cid"))
                                             .setApiName(literally("api"))
                                             .setTokenUrl(literally("http://token"))
                                             .setScope(literally("scope"))
                                             .build();
        Guice.createInjector(new TimeModule(),
                             new ExecutorModule(),
                             new AbstractModule() {
                                 @Override
                                 protected void configure() {
                                     bind(VarStore.class).toInstance(new InMemoryVarStore());
                                 }
                             },
                             module)
             .getBinding(OAuth2TokenManager.class);
    }
}
