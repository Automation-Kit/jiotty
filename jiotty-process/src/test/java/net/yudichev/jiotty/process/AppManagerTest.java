package net.yudichev.jiotty.process;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.app.ApplicationLifecycleControl;
import net.yudichev.jiotty.common.lang.MutableReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AppManagerTest {
    private final MutableReference<ApplicationLifecycleControl> appLifecycleControl = new MutableReference<>();
    @Mock
    private ApplicationLifecycleControl processLifecycleControl;
    private @Nullable AppManager appManager;

    @AfterEach
    void tearDown() {
        if (appManager != null) {
            appManager.stop();
        }
    }

    /// The app's panic stops the whole process, which its supervisor then restarts.
    @Test
    void aPanicOfTheAppPanicsTheProcess() {
        appManager = new AppManager(_ -> new AbstractModule() {
            @Override
            protected void configure() {
                requestInjection(new Object() {
                    @Inject
                    void capture(ApplicationLifecycleControl lifecycleControl) {
                        appLifecycleControl.set(lifecycleControl);
                    }
                });
            }
        }, Guice.createInjector(), processLifecycleControl);
        appManager.start();
        var cause = new RuntimeException("full");

        appLifecycleControl.get().panic("executor UserManagement has a full queue", cause);

        verify(processLifecycleControl).panic("executor UserManagement has a full queue", cause);
    }
}
