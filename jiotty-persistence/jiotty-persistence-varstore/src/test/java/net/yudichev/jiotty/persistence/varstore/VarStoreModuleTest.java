package net.yudichev.jiotty.persistence.varstore;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.time.TimeModule;
import net.yudichev.jiotty.persistence.db.DataSourceFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Paths;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

class VarStoreModuleTest {
    static Stream<Arguments> configure() {
        return Stream.of(arguments(true, true),
                         arguments(true, false),
                         arguments(false, true));
    }

    @ParameterizedTest
    @MethodSource
    void configure(boolean withDataSource, boolean withPath) {
        var builder = VarStoreModule.builder();
        if (withDataSource) {
            builder.withDataSourceFactory(literally(mock(DataSourceFactory.class)));
        }
        if (withPath) {
            builder.withPath(literally(Paths.get(".")));
        }
        var injector = Guice.createInjector(new TimeModule(),
                                            new ExecutorModule(),
                                            builder.build());
        assertThat(injector.getBinding(VarStore.class)).isNotNull();
    }

    @Test
    void failsWhenNeitherPathNorDataSourceSet() {
        assertThatThrownBy(() -> Guice.createInjector(new TimeModule(),
                                                      new ExecutorModule(),
                                                      VarStoreModule.builder().build()))
                .hasMessageContaining("At least one of 'path', 'dataSourceFactory' is required");
    }
}