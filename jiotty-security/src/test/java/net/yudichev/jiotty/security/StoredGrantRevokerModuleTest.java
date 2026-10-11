package net.yudichev.jiotty.security;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

class StoredGrantRevokerModuleTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void exposesARevokerUnderTheSpecifiedAnnotation(boolean withRevocationUrl) {
        Annotation annotation = uniqueAnnotation();
        StoredGrantRevokerModule.Builder builder = StoredGrantRevokerModule.builder()
                                                                           .setClientId(literally("cid"))
                                                                           .withClientSecret(literally("cs"))
                                                                           .setApiName(literally("api"))
                                                                           .setTokenUrl(literally("http://token"))
                                                                           .setScope(literally("scope"))
                                                                           .withAnnotation(forAnnotation(annotation));
        if (withRevocationUrl) {
            builder.withRevocationUrl(literally("http://revoke"));
        }
        ExposedKeyModule<StoredGrantRevoker> module = builder.build();

        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(StoredGrantRevoker.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(StoredGrantRevokerImpl.class);
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }
}
