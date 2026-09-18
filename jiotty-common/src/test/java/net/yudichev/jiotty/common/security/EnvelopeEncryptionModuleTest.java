package net.yudichev.jiotty.common.security;

import com.google.inject.Guice;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import net.yudichev.jiotty.common.inject.LifecycleComponent;
import net.yudichev.jiotty.common.keystore.KeyStoreAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.inject.SpecifiedAnnotation.forAnnotation;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EnvelopeEncryptionModuleTest {
    @Mock
    private KeyStoreAccess keyStoreAccess;

    @Test
    void exposesEnvelopeEncryption() {
        var injector = Guice.createInjector(EnvelopeEncryptionModule.builder()
                                                                   .setMasterKeyAlias(literally("master-key-alias"))
                                                                   .setKeyStoreAccess(literally(keyStoreAccess))
                                                                   .build());

        assertThat(injector.getBinding(EnvelopeEncryption.class)).isNotNull();
        assertThat(injector.findBindingsByType(new TypeLiteral<LifecycleComponent>() {}))
                .as("the implementation opens the key store on start, so it has to reach the application's lifecycle set")
                .isNotEmpty();
    }

    /// Two installations coexist only if each is asked for under its own annotation, which is what lets a store bind one alongside another consumer's.
    @Test
    void annotatedInstallationsCoexist() {
        var injector = Guice.createInjector(EnvelopeEncryptionModule.builder()
                                                                   .setMasterKeyAlias(literally("alias-one"))
                                                                   .setKeyStoreAccess(literally(keyStoreAccess))
                                                                   .withAnnotation(forAnnotation(Names.named("one")))
                                                                   .build(),
                                            EnvelopeEncryptionModule.builder()
                                                                    .setMasterKeyAlias(literally("alias-two"))
                                                                    .setKeyStoreAccess(literally(keyStoreAccess))
                                                                    .withAnnotation(forAnnotation(Names.named("two")))
                                                                    .build());

        assertThat(injector.getBinding(Key.get(EnvelopeEncryption.class, Names.named("one")))).isNotNull();
        assertThat(injector.getBinding(Key.get(EnvelopeEncryption.class, Names.named("two")))).isNotNull();
    }
}
