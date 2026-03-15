package net.yudichev.jiotty.user.ui;

import com.google.inject.Guice;
import net.yudichev.jiotty.common.async.ExecutorModule;
import net.yudichev.jiotty.common.varstore.VarStoreModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;

class SingleUserHttpServerModuleTest {
    @TempDir
    private Path tempDir;

    @Test
    void configure() {
        Guice.createInjector(new ExecutorModule(),
                             new UIServerModule(),
                             VarStoreModule.builder()
                                           .setPath(tempDir.resolve("data.json"))
                                           .build(),
                             SingleUserHttpServerModule.builder().setListenPort(literally(4568)).build());
    }
}
