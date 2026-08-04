package net.yudichev.jiotty.common.graph.server;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaseGraphBasedServerTest {
    private ProgrammableClock clock;
    private SchedulingExecutor executor;
    private TestServer server;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        executor = clock.createSingleThreadedSchedulingExecutor("test");
        server = new TestServer();
        server.start();
        clock.tick();
    }

    @Test
    void stopHandsGraphTeardownToTheExecutorAndReturns() {
        server.stop();

        assertThat(server.graphIsActive()).as("stop returns without waiting for the teardown").isTrue();

        // The executor's drain runs the queued teardown, as it does at shutdown
        executor.close();

        assertThat(server.graphIsActive()).as("the drain closes the graph").isFalse();
    }

    @Test
    void panic_messageOnly_handlePanicReceivesMessageAndNullCause() {
        server.runner().panic("disk full", null);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("disk full");
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("disk full");
    }

    @Test
    void panic_causeOnly_handlePanicReceivesNullMessageAndCause() {
        var cause = new IllegalStateException("boom");
        server.runner().panic(null, cause);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isNull();
            assertThat(call.cause).isSameAs(cause);
        });
        assertThat(server.panicReason).contains("boom");
    }

    @Test
    void panic_messageAndCause_handlePanicReceivesBoth_panicReasonComposes() {
        var cause = new IllegalStateException("disk gone");
        server.runner().panic("write failed", cause);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("write failed");
            assertThat(call.cause).isSameAs(cause);
        });
        assertThat(server.panicReason).startsWith("write failed").contains("disk gone");
    }

    @Test
    void panic_neitherMessageNorCause_handlePanicReceivesNulls() {
        server.runner().panic(null, null);
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isNull();
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("Reason unknown");
    }

    @Test
    void panic_oneArgOverload_delegatesWithNullCause() {
        server.runner().panic("legacy reason");
        clock.tick();

        assertThat(server.handlePanicCalls).singleElement().satisfies(call -> {
            assertThat(call.message).isEqualTo("legacy reason");
            assertThat(call.cause).isNull();
        });
        assertThat(server.panicReason).isEqualTo("legacy reason");
    }

    private final class TestServer extends BaseGraphBasedServer {
        final List<HandlePanicCall> handlePanicCalls = new ArrayList<>();
        private @Nullable GraphRunner capturedRunner;

        TestServer() {
            super(() -> executor, clock, () -> 0.5);
        }

        GraphRunner runner() {
            return checkRunner();
        }

        boolean graphIsActive() {
            return graphActive();
        }

        @Override
        protected void createNodes(GraphRunner graphRunner, NodeRegistrator registrator) {
            capturedRunner = graphRunner;
        }

        @Override
        protected void recordState() {
        }

        @Override
        protected void handlePanic(@Nullable String message, @Nullable Throwable cause) {
            handlePanicCalls.add(new HandlePanicCall(message, cause));
        }

        private GraphRunner checkRunner() {
            if (capturedRunner == null) {
                throw new IllegalStateException("Graph not yet created");
            }
            return capturedRunner;
        }
    }

    private record HandlePanicCall(@Nullable String message, @Nullable Throwable cause) {}
}
