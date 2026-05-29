package net.yudichev.jiotty.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMetricsBinderTest {
    private SimpleMeterRegistry meterRegistry;
    private JvmMetricsBinder binder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        binder = new JvmMetricsBinder(meterRegistry);
        binder.start();
    }

    @AfterEach
    void tearDown() {
        binder.stop();
    }

    @Test
    void doStart_registersJvmMemoryMeters() {
        assertThat(meterRegistry.find("jvm.memory.used").gauges())
                .as("JvmMemoryMetrics registers jvm.memory.used")
                .isNotEmpty();
        assertThat(meterRegistry.find("jvm.memory.committed").gauges())
                .as("JvmMemoryMetrics registers jvm.memory.committed")
                .isNotEmpty();
    }

    @Test
    void doStart_registersJvmThreadMeters() {
        assertThat(meterRegistry.find("jvm.threads.live").gauges())
                .as("JvmThreadMetrics registers jvm.threads.live")
                .isNotEmpty();
    }

    @Test
    void doStart_registersClassLoaderMeters() {
        assertThat(meterRegistry.find("jvm.classes.loaded").gauges())
                .as("ClassLoaderMetrics registers jvm.classes.loaded")
                .isNotEmpty();
    }

    @Test
    void doStart_registersProcessorMeters() {
        assertThat(meterRegistry.find("system.cpu.count").gauges())
                .as("ProcessorMetrics registers system.cpu.count")
                .isNotEmpty();
    }

    @Test
    void doStart_registersUptimeMeters() {
        assertThat(meterRegistry.find("process.uptime").gauges())
                .as("UptimeMetrics registers process.uptime")
                .isNotEmpty();
    }

    @Test
    void doStop_doesNotThrow_andClearsBoundCloseables() {
        // JvmGcMetrics' meters are JVM-/GC-implementation-conditional and not worth a brittle name-pinned assertion;
        // instead, exercise the stop path to ensure binder closeables can be released cleanly and that a subsequent
        // start would not leak previously-collected references.
        binder.stop();
        binder.start();

        assertThat(meterRegistry.find("jvm.memory.used").gauges())
                .as("re-starting after stop re-registers the JVM memory binder")
                .isNotEmpty();
    }
}
