package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.graph.Graph;
import net.yudichev.jiotty.common.graph.server.TestGraphRunner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// A graph that runs on a [ProgrammableClock]'s executor and records the failures of its waves, for the node tests of the Octopus price graphs.
public record TestGraph(ProgrammableClock clock, Graph graph, TestGraphRunner runner, List<RuntimeException> waveFailures) {
    public static TestGraph startingAt(Instant start) {
        var clock = new ProgrammableClock();
        clock.setTime(start);
        List<RuntimeException> waveFailures = new ArrayList<>();
        var graph = new Graph(clock, waveFailures::add);
        return new TestGraph(clock, graph, new TestGraphRunner(graph, clock.createSingleThreadedSchedulingExecutor("test")), waveFailures);
    }

    /// Runs a wave, and everything it triggers.
    public void runWave() {
        runner.runWaves();
        clock.tick();
    }

    public void assertNoWaveFailed() {
        assertThat(waveFailures).as("failures of the graph's waves").isEmpty();
    }
}
