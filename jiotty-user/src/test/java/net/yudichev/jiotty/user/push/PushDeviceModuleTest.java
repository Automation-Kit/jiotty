package net.yudichev.jiotty.user.push;

import com.google.inject.Guice;
import com.google.inject.Key;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.inject.ExposedKeyModule;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PushDeviceModuleTest {
    @Test
    void build_withVarStore_exposesStore(@Mock VarStore varStore) {
        ExposedKeyModule<PushDeviceStore> module = PushDeviceModule.builder()
                                                                   .withVarStore(literally(varStore))
                                                                   .build();

        assertThat(module.getExposedKey()).isEqualTo(Key.get(PushDeviceStore.class));

        var injector = Guice.createInjector(ExecutorModule.builder().build(), module);
        assertThat(injector.getBinding(module.getExposedKey())).isNotNull();
    }
}
