package net.yudichev.jiotty.security;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.util.Map;

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
