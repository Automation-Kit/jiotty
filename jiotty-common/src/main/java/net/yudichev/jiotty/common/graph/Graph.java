package net.yudichev.jiotty.common.graph;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class Graph extends BaseIdempotentCloseable {
    private static final Logger logger = LogManager.getLogger(Graph.class);

    private final TreeSet<NodeState> nodesPendingTrigger = new TreeSet<>(Comparator.comparing(NodeState::rank).thenComparing(NodeState::id));
    private final List<NodeState> nodesTriggeredInWave = new ArrayList<>();
    private final IdentityHashMap<Node, NodeState> statesByNode = new IdentityHashMap<>();
    private final CurrentDateTimeProvider timeProvider;
    private final Consumer<RuntimeException> exceptionHandler;
    private final Set<NodeState> nodesNeedingReRanking = new HashSet<>();
    private final AtomicReference<Thread> callerThread = new AtomicReference<>();
    private int nodeIdGenerator;
    @Nullable
    private Instant waveTime;
    private int waveId;

    public Graph(CurrentDateTimeProvider timeProvider, Consumer<RuntimeException> exceptionHandler) {
        this.timeProvider = checkNotNull(timeProvider);
        this.exceptionHandler = checkNotNull(exceptionHandler);
    }

    public <T extends Node> T registerNode(String name, T node) {
        assertCallingThreadConsistent();
        checkState(!inWave(), "Cannot register nodes in-wave");
        var nodeState = new NodeState(name, node);
        checkArgument(statesByNode.putIfAbsent(node, nodeState) == null, "Node already registered: %s", node);
        nodeState.initialise();
        return node;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    public void runWaves() {
        checkState(!isClosed(), "Closed");
        while (wave()) ;
    }

    public final Instant waveTime() {
        assertCallingThreadConsistent();
        return checkNotNull(waveTime, "not in wave");
    }

    public final int waveId() {
        assertCallingThreadConsistent();
        return waveId;
    }

    public final boolean inWave() {
        assertCallingThreadConsistent();
        return waveTime != null;
    }

    @Override
    protected void doClose() {
        assertCallingThreadConsistent();
        nodesPendingTrigger.clear();
        nodesTriggeredInWave.clear();
        nodesNeedingReRanking.clear();
        Closeable.closeSafelyIfNotNull(logger, statesByNode.values());
    }

    boolean wave() {
        assertCallingThreadConsistent();
        checkState(!inWave(), "recursive call to wave()");

        if (nodesPendingTrigger.isEmpty()) {
            return false;
        }
        waveId++;
        assert setWaveIdInMdc();
        waveTime = timeProvider.currentInstant();
        NodeState currentNode = nodesPendingTrigger.first();
        try {
            do {
                nodesTriggeredInWave.add(currentNode);
                boolean stateModified = currentNode.node.wave();
                if (stateModified) {
                    // mark all children as pending trigger
                    currentNode.children.forEach(NodeState::triggerInNextWave);
                }
                nodesPendingTrigger.remove(currentNode);
                currentNode = nodesPendingTrigger.higher(currentNode);
            } while (currentNode != null);
        } catch (RuntimeException e) {
            try {
                exceptionHandler.accept(e);
            } catch (RuntimeException ex) {
                logger.warn("Exception in exception handler", e);
            }
            // exception stops the waves
            return false;
        } finally {
            assert clearWaveIdInMdc();
            waveTime = null;
            nodesTriggeredInWave.forEach(nodeState -> {
                try {
                    nodeState.node.afterWave();
                } catch (RuntimeException e) {
                    logger.warn("Exception in {}.afterWave()", nodeState.name(), e);
                }
            });
            if (logger.isDebugEnabled()) {
                logger.debug("Wave {}: nodes triggered: {}, nodes pending: {}",
                             waveId, new ArrayList<>(nodesTriggeredInWave), new ArrayList<>(nodesPendingTrigger));
            }
            nodesTriggeredInWave.clear();
        }
        return !nodesPendingTrigger.isEmpty();
    }

    private void assertCallingThreadConsistent() {
        var currentThread = Thread.currentThread();
        boolean justAssignedThread = callerThread.compareAndSet(null, currentThread);
        assert justAssignedThread || callerThread.getOpaque() == currentThread
                : "graph called on multiple threads: " + callerThread.getOpaque() + " and " + currentThread;
    }

    private boolean setWaveIdInMdc() {
        ThreadContext.put("waveId", '[' + Integer.toString(waveId) + ']');
        return true;
    }

    private static boolean clearWaveIdInMdc() {
        ThreadContext.remove("waveId");
        return true;
    }

    private void reRank() {
        // if nodesPendingTrigger is not empty, need to rebuild it as mutating ranks in-place will break the sorting
        List<NodeState> nodesPendingTriggerCopy = null;
        if (!nodesPendingTrigger.isEmpty()) {
            nodesPendingTriggerCopy = new ArrayList<>(nodesPendingTrigger);
            nodesPendingTrigger.clear();
        }

        while (!nodesNeedingReRanking.isEmpty()) {
            Iterator<NodeState> iterator = nodesNeedingReRanking.iterator();
            while (iterator.hasNext()) {
                NodeState nodeState = iterator.next();
                assert nodeState.rank <= 0 : "nodesNeedingReRanking contains node with final rank";
                assert !nodeState.parents.isEmpty() : "asked to re-rank node with no parents";
                boolean allParentsHaveFinalRank = true;
                for (NodeState parent : nodeState.parents) {
                    if (parent.rank != 0) {
                        nodeState.rank = Math.min(-Math.abs(parent.rank) - 1, nodeState.rank);
                    }
                    if (parent.rank <= 0) {
                        allParentsHaveFinalRank = false;
                    }
                }
                if (allParentsHaveFinalRank) {
                    // finalise our rank too
                    assert nodeState.rank < 0;
                    nodeState.rank = -nodeState.rank;
                    iterator.remove();
                }
            }
        }

        if (nodesPendingTriggerCopy != null) {
            nodesPendingTrigger.addAll(nodesPendingTriggerCopy);
        }
    }

    private final class NodeState extends BaseIdempotentCloseable implements NodeContext {
        private final List<NodeState> parents = new ArrayList<>();
        private final List<NodeState> children = new ArrayList<>();
        private final String name;
        private final Node node;
        private final int id;
        /// @implSpec negative -> non-final in process of computing, 0 - invalid and needs re-ranking, positive - final ranked
        private int rank = 1; // node that jas just been created has a rank of 1 (as no parents)

        public NodeState(String name, Node node) {
            this.name = name;
            this.node = node;
            id = nodeIdGenerator++;
        }

        public void initialise() {
            node.initialise(this);
            // always trigger on init to calculate initial state
            triggerInNextWave();
        }

        @Override
        public NodeContext subscribeTo(Node node) {
            assertCallingThreadConsistent();
            checkState(!inWave(), "Cannot subscribe in-wave");
            NodeState parent = statesByNode.get(node);
            checkArgument(parent != null, "Node %s is not registered", node);

            checkArgument(!parents.contains(parent), "Dependency %s -> %s already exists", this, parent);
            parents.add(parent);
            markAsNeedReranking();
            parent.children.add(this);

            Set<NodeState> visitedNodes = new HashSet<>(statesByNode.size());
            forAllChildrenRecursively(nodeState -> {
                if (!visitedNodes.add(nodeState)) {
                    // undo the effects of the whole method
                    parents.remove(parent);
                    parent.children.remove(this);
                    throw new IllegalArgumentException("Adding dependency " + this + " -> " + parent + " creates a cycle involving node " + nodeState);
                }
                nodeState.markAsNeedReranking();
            });

            reRank();

            return this;
        }

        @Override
        public Node node() {
            assertCallingThreadConsistent();
            return node;
        }

        public int rank() {
            return rank;
        }

        public int id() {
            return id;
        }

        @Override
        public String name() {
            assertCallingThreadConsistent();
            return name;
        }

        @Override
        public boolean triggerInNextWave() {
            assertCallingThreadConsistent();
            return nodesPendingTrigger.add(this);
        }

        @Override
        public boolean triggerMeAndParentsInNextWave() {
            boolean triggered = triggerInNextWave();
            for (NodeState parent : parents) {
                triggered |= parent.triggerMeAndParentsInNextWave();
            }
            return triggered;
        }

        @Override
        public Graph graph() {
            assertCallingThreadConsistent();
            return Graph.this;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        protected void doClose() {
            node.close();
        }

        private void markAsNeedReranking() {
            rank = 0;
            nodesNeedingReRanking.add(this);
        }

        private void forAllChildrenRecursively(Consumer<NodeState> consumer) {
            for (NodeState child : children) {
                consumer.accept(child);
                child.forAllChildrenRecursively(consumer);
            }
        }
    }
}
