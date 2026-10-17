package net.yudichev.jiotty.security;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.time.TimeModule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.Annotation;
import java.util.function.Function;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.GuiceUtil.uniqueAnnotation;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class StoredGrantRevokerModuleTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void exposesARevokerUnderTheSpecifiedAnnotation(String configuration,
                                                    Function<StoredGrantRevokerModule.Builder, StoredGrantRevokerModule.Builder> configurer) {
        Annotation annotation = uniqueAnnotation();
        ExposedKeyModule<StoredGrantRevoker> module = configurer.apply(StoredGrantRevokerModule.builder()
                                                                                               .setApiName(literally("api"))
                                                                                               .setScope(literally("scope"))
                                                                                               .withAnnotation(forAnnotation(annotation)))
                                                                .build();

        var injector = Guice.createInjector(TimeModule.builder().build(), ExecutorModule.builder().build(), module);

        assertThat(module.getExposedKey()).isEqualTo(Key.get(StoredGrantRevoker.class, annotation));
        assertThat(injector.getInstance(module.getExposedKey())).isInstanceOf(StoredGrantRevokerImpl.class);
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {})).isNotEmpty();
    }

    static Stream<Arguments> exposesARevokerUnderTheSpecifiedAnnotation() {
        Function<StoredGrantRevokerModule.Builder, StoredGrantRevokerModule.Builder> oneClient =
                builder -> builder.withClientId(literally("cid")).withClientSecret(literally("cs")).withTokenUrl(literally("http://token"));
        Function<StoredGrantRevokerModule.Builder, StoredGrantRevokerModule.Builder> rfc7009 = builder -> builder.withRevocationUrl(literally("http://revoke"));
        return Stream.of(arguments("one client, rotation", oneClient),
                         arguments("one client, RFC 7009", oneClient.andThen(rfc7009)),
                         arguments("every client, RFC 7009", rfc7009));
    }
}
