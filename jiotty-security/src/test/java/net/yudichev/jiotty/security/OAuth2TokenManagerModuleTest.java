package net.yudichev.jiotty.security;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.varstore.InMemoryVarStore;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class OAuth2TokenManagerModuleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void configures(boolean withLoginUrl) {
        OAuth2TokenManagerModule.Builder builder = OAuth2TokenManagerModule.builder()
                                                                           .setClientId(literally("cid"))
                                                                           .setClientSecret(literally("cs"))
                                                                           .setApiName(literally("api"))
                                                                           .setTokenUrl(literally("http://token"))
                                                                           .setScope(literally("scope"));
        if (withLoginUrl) {
            builder.withLoginUrl(literally("http://login"));
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
}
