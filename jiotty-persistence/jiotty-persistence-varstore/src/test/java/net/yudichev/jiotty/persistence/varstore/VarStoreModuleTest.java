package net.yudichev.jiotty.persistence.varstore;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Paths;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(MockitoExtension.class)
class VarStoreModuleTest {
    @Mock
    private DataSourceFactory dataSourceFactory;
    @Mock
    private KeyStoreAccess keyStoreAccess;

    static Stream<Arguments> configure() {
        return Stream.of(arguments(true, false),
                         arguments(false, true));
    }

    @ParameterizedTest
    @MethodSource
    void configure(boolean withDataSource, boolean withPath) {
        var builder = VarStoreModule.builder();
        if (withDataSource) {
            builder.withDataSourceFactory(literally(dataSourceFactory));
        }
        if (withPath) {
            builder.withPath(literally(Paths.get(".")));
        }
        var injector = Guice.createInjector(TimeModule.builder().build(),
                                            ExecutorModule.builder().build(),
                                            builder.build());
        assertThat(injector.getBinding(VarStore.class)).isNotNull();
    }

    /// Each selects a different store, so asking for both is a configuration mistake.
    @Test
    void failsWhenBothPathAndDataSourceSet() {
        assertThatThrownBy(() -> Guice.createInjector(TimeModule.builder().build(),
                                                      ExecutorModule.builder().build(),
                                                      VarStoreModule.builder()
                                                                    .withDataSourceFactory(literally(dataSourceFactory))
                                                                    .withPath(literally(Paths.get(".")))
                                                                    .build()))
                .hasMessageContaining("'path' is for the file-backed store");
    }

    @Test
    void failsWhenNeitherPathNorDataSourceSet() {
        assertThatThrownBy(() -> Guice.createInjector(TimeModule.builder().build(),
                                                      ExecutorModule.builder().build(),
                                                      VarStoreModule.builder().build()))
                .hasMessageContaining("At least one of 'path', 'dataSourceFactory' is required");
    }

    @Test
    void configureWithEncryption() {
        var injector = Guice.createInjector(TimeModule.builder().build(),
                                            ExecutorModule.builder().build(),
                                            VarStoreModule.builder()
                                                          .withDataSourceFactory(literally(dataSourceFactory))
                                                          .withEncryptionKeyAlias(literally("master-key-alias"))
                                                          .withKeyStoreAccess(literally(keyStoreAccess))
                                                          .build());

        assertThat(injector.getBinding(VarStore.class)).isNotNull();
    }

    @Test
    void failsWhenEncryptionAliasSetWithoutKeyStoreAccess() {
        assertThatThrownBy(() -> Guice.createInjector(TimeModule.builder().build(),
                                                      ExecutorModule.builder().build(),
                                                      VarStoreModule.builder()
                                                                    .withDataSourceFactory(literally(dataSourceFactory))
                                                                    .withEncryptionKeyAlias(literally("some-alias"))
                                                                    .build()))
                .hasMessageContaining("withKeyStoreAccess is required");
    }
}
