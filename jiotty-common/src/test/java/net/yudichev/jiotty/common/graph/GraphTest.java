package net.yudichev.jiotty.common.graph;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class GraphTest {
    private final Map<String, TestNode> nodesToTriggerByTriggeringNodeName = new HashMap<>();
    private final Map<String, TestNode> nodesToTriggerWithParentsByTriggeringNodeName = new HashMap<>();
    private final List<String> triggeredNodes = new ArrayList<>();
    private final List<String> afterWaveNodes = new ArrayList<>();
    @Mock
    private Consumer<RuntimeException> exceptionHandler;
    private Graph graph;
    private boolean nodeWaveReturnValue = true;
    private TestNode node1;
    private TestNode node2;
    private TestNode node3;
    private TestNode node4;
    private TestNode node5;
    private ProgrammableClock clock;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.advanceTimeAndTick(Duration.ofMinutes(5));
        graph = new Graph(clock, exceptionHandler);
    }

    static void generatePermutations(List<Integer> current, Set<Integer> remaining, List<List<Integer>> result) {
        if (remaining.isEmpty()) {
            result.add(new ArrayList<>(current));
            return;
        }

        for (Integer num : new ArrayList<>(remaining)) {
            current.add(num);
            Set<Integer> nextRemaining = new HashSet<>(remaining);
            nextRemaining.remove(num);
            generatePermutations(current, nextRemaining, result);
            current.removeLast();
        }
    }

    static Stream<List<Integer>> graphStructure() {
        List<Integer> nums = List.of(0, 1, 2, 3, 4);
        List<List<Integer>> result = new ArrayList<>();
        generatePermutations(new ArrayList<>(), new HashSet<>(nums), result);
        return result.stream();
    }

    @ParameterizedTest
    @MethodSource
    void graphStructure(List<Integer> subscriptionOrder) {
        createNodes(subscriptionOrder);
        graph.runWaves();
        // initially everything is triggered once
        assertThat(triggeredNodes).containsExactly("1", "5", "2", "4", "3");
        triggeredNodes.clear();

        node1.trigger();
        node5.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "5", "2", "4", "3");

        triggeredNodes.clear();
        node1.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "2", "4", "3");

        triggeredNodes.clear();
        node5.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("5", "3");

        triggeredNodes.clear();
        node2.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("2");

        triggeredNodes.clear();
        node4.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("4", "3");

        triggeredNodes.clear();
        node3.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("3");

        nodeWaveReturnValue = false;

        triggeredNodes.clear();
        node1.trigger();
        node5.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "5");

        triggeredNodes.clear();
        node1.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1");

        triggeredNodes.clear();
        node5.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("5");

        triggeredNodes.clear();
        node2.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("2");

        triggeredNodes.clear();
        node4.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("4");

        triggeredNodes.clear();
        node3.trigger();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("3");

        triggeredNodes.clear();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).isEmpty();
    }

    @Test
    void nodesTriggeringOtherNodesInWave() {
        createNodes();
        assertThat(graph.wave()).isFalse();
        triggeredNodes.clear();

        willTrigger("4", node1);
        node4.trigger();
        assertThat(graph.wave()).isTrue();
        assertThat(triggeredNodes).containsExactly("4", "3");
        triggeredNodes.clear();
        clearTriggers();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "2", "4", "3");
        triggeredNodes.clear();

        willTrigger("3", node5);
        willTrigger("5", node3);
        node5.trigger();
        assertThat(graph.wave()).isTrue();
        assertThat(triggeredNodes).containsExactly("5", "3");
        triggeredNodes.clear();
        clearTriggers();

        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("5", "3");
        triggeredNodes.clear();
        clearTriggers();
    }

    @Test
    void nodeCanTriggerItselfForTheNextWave() {
        nodeWaveReturnValue = false;
        node1 = graph.registerNode("1", new TestNode("1"));
        assertThat(graph.wave()).isFalse();
        triggeredNodes.clear();

        willTrigger("1", node1);
        node1.trigger();
        assertThat(graph.wave()).isTrue();
        assertThat(triggeredNodes).containsExactly("1");

        triggeredNodes.clear();
        clearTriggers();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1");
    }

    @Test
    void nodesTriggeringOthersWithParentsAndNodeWaveReturningFalse() {
        nodeWaveReturnValue = false;

        createNodes();
        assertThat(graph.wave()).isFalse();
        triggeredNodes.clear();

        // 2 will request 3 to be triggered with parents
        // some of the parents will be satisfied in the same wave, but only those that have lower rank than the one already executed (2)
        // these are 4 and 3; 1 and 5 will be scheduled for the next wave
        willTriggerWithParents("2", node3);
        node2.trigger();
        assertThat(graph.wave()).isTrue();
        assertThat(triggeredNodes).containsExactly("2", "4", "3");
        triggeredNodes.clear();
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "5");
    }

    @Test
    void nodesTriggeringOthersWithParentsAndNodeWaveReturningTrue() {
        nodeWaveReturnValue = true;

        createNodes();
        assertThat(graph.wave()).isFalse();
        triggeredNodes.clear();

        // 2 will request 3 to be triggered with parents
        // some of the parents will be satisfied in the same wave, but only those that have lower rank than the one already executed (2)
        // these are 4 and 3; 1 and 5 will be scheduled for the next wave
        willTriggerWithParents("2", node3);
        node2.trigger();
        assertThat(graph.wave()).isTrue();
        assertThat(triggeredNodes).containsExactly("2", "4", "3");
        triggeredNodes.clear();
        clearTriggers();

        // in this wave, 1 and 5 are scheduled, but since they resurnt true from the wave(), the whole graph is executed
        assertThat(graph.wave()).isFalse();
        assertThat(triggeredNodes).containsExactly("1", "5", "2", "4", "3");
    }

    @Test
    void failsIfCycle() {
        node1 = graph.registerNode("1", new TestNode("1"));
        node2 = graph.registerNode("2", new TestNode("2"));
        node3 = graph.registerNode("3", new TestNode("3"));

        /*
         1 -> 2 -> 3 -> 1
         */
        node1.subscribeTo(node2);
        node2.subscribeTo(node3);
        assertThatThrownBy(() -> node3.subscribeTo(node1)).hasMessageContaining("cycle");
    }

    @Test
    void afterWaveCalledForEveryNodeTriggeredInWave() {
        createNodes();
        assertThat(graph.wave()).isFalse();
        // afterWave is called for every node triggered in the wave, in the same order they were waved
        assertThat(afterWaveNodes).containsExactlyElementsOf(triggeredNodes);
        verifyNoInteractions(exceptionHandler);
    }

    @Test
    void afterWaveExceptionIsRoutedToExceptionHandlerAndStopsWaves() {
        createNodes();
        var failure = new RuntimeException("afterWave failed");
        node2.afterWaveFailure = failure;

        // an exception in afterWave() is handled exactly like in any other node method: passed to the handler and the waves stop
        assertThat(graph.wave()).isFalse();
        verify(exceptionHandler).accept(failure);
    }

    @Test
    void afterWaveNotCalledWhenNodeWaveThrows() {
        createNodes();
        var failure = new RuntimeException("wave failed");
        node4.waveFailure = failure;

        assertThat(graph.wave()).isFalse();
        verify(exceptionHandler).accept(failure);
        // wave() failed, so the node lifecycle is broken: afterWave() must not be called on any node
        assertThat(afterWaveNodes).isEmpty();
    }

    private void clearTriggers() {
        nodesToTriggerByTriggeringNodeName.clear();
        nodesToTriggerWithParentsByTriggeringNodeName.clear();
    }

    private void willTrigger(String source, TestNode dest) {
        nodesToTriggerByTriggeringNodeName.put(source, dest);
    }

    private void willTriggerWithParents(String source, TestNode dest) {
        nodesToTriggerWithParentsByTriggeringNodeName.put(source, dest);
    }

    private void createNodes() {
        createNodes(List.of(0, 1, 2, 3, 4));
    }

    private void createNodes(List<Integer> subscriptionOrder) {
        /*
        1---    5
       / \  \ /
      2   4  |
           \/
            3
         */
        node1 = graph.registerNode("1", new TestNode("1"));
        node2 = graph.registerNode("2", new TestNode("2"));
        node3 = graph.registerNode("3", new TestNode("3"));
        node4 = graph.registerNode("4", new TestNode("4"));
        node5 = graph.registerNode("5", new TestNode("5"));

        List<Runnable> subscriptions = List.of(() -> node2.subscribeTo(node1),
                                               () -> node4.subscribeTo(node1),
                                               () -> node3.subscribeTo(node4),
                                               () -> node3.subscribeTo(node1),
                                               () -> node3.subscribeTo(node5));
        subscriptionOrder.forEach(i -> subscriptions.get(i).run());
    }

    private class TestNode extends BaseNode {
        private final String name;
        private @Nullable RuntimeException waveFailure;
        private @Nullable RuntimeException afterWaveFailure;

        public TestNode(String name) {
            this.name = name;
        }

        @Override
        public void initialise() {
            assertThat(nodeContext().node()).isSameAs(this);
            assertThat(nodeContext().name()).isEqualTo(name);
            assertThat(nodeContext().graph()).isSameAs(graph);
        }

        @Override
        public boolean wave() {
            assertThat(nodeContext().graph().waveTime()).isEqualTo(clock.currentInstant());
            triggeredNodes.add(name);
            if (waveFailure != null) {
                throw waveFailure;
            }
            TestNode nodeToTrigger = nodesToTriggerByTriggeringNodeName.get(name);
            if (nodeToTrigger != null) {
                assertThat(nodeToTrigger.trigger()).isTrue();
                assertThat(nodeToTrigger.trigger()).isFalse();
            }
            TestNode nodeToTriggerWithParents = nodesToTriggerWithParentsByTriggeringNodeName.get(name);
            if (nodeToTriggerWithParents != null) {
                assertThat(nodeToTriggerWithParents.triggerMeAndParents()).isTrue();
                assertThat(nodeToTriggerWithParents.triggerMeAndParents()).isFalse();
            }
            return nodeWaveReturnValue;
        }

        @Override
        public void afterWave() {
            afterWaveNodes.add(name);
            if (afterWaveFailure != null) {
                throw afterWaveFailure;
            }
        }
    }
}
