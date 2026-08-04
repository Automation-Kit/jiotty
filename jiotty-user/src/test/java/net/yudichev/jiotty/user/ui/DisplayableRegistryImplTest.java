package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisplayableRegistryImplTest {
    private final List<String> closedSubscriptions = new ArrayList<>();
    private SchedulingExecutor executor;
    private DisplayableRegistryImpl registry;

    @BeforeEach
    void setUp() {
        var clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("ui");
        registry = new DisplayableRegistryImpl(() -> executor);
        registry.start();
    }

    @Test
    void unregisteringWhileStartedReleasesTheDisplayablesSubscription() {
        Closeable registration = registry.register(new TestDisplayable());

        registration.close();

        assertThat(closedSubscriptions).containsExactly("d1");
    }

    @Test
    void unregisteringAfterTheRegistryStoppedDoesNothing() {
        // An owner unregistering from its own teardown can reach this registry after it — and its executor — have stopped. Everything the unregistration
        // would touch is moot by then, so it does nothing.
        Closeable registration = registry.register(new TestDisplayable());
        registry.stop();
        executor.close();

        registration.close();

        assertThat(closedSubscriptions).isEmpty();
    }

    private final class TestDisplayable implements Displayable {
        @Override
        public String getId() {
            return "d1";
        }

        /// Drives the throttling-consumer path, which is the one that submits to the registry's executor.
        @Override
        public boolean supportsData() {
            return true;
        }

        @Override
        public Closeable subscribeForUpdates(Runnable updatesAvailable) {
            return () -> closedSubscriptions.add(getId());
        }
    }
}
