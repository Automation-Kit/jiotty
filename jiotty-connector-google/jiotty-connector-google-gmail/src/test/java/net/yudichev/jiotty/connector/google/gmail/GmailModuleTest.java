package net.yudichev.jiotty.connector.google.gmail;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.connector.google.common.GoogleAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

@ExtendWith(MockitoExtension.class)
class GmailModuleTest {
    @Test
    void configure(@Mock GoogleAuthorization authorization) {
        var module = GmailModule.builder()
                                .withAuthorization(literally(authorization))
                                .build();
        Guice.createInjector(ExecutorModule.builder().build(), module).getBinding(module.getExposedKey());
    }
}