package net.yudichev.jiotty.common.inject;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaseLifecycleComponentTest {
    private final List<String> actionsRun = new ArrayList<>();
    private final TestComponent component = new TestComponent();

    @Test
    void ifNotStoppedRunsTheActionWhileStarted() {
        component.start();

        component.guardedRun("while-started");

        assertThat(actionsRun).containsExactly("while-started");
    }

    /// A producer commonly delivers its current value synchronously when subscribed to, which happens on the starting thread inside
    /// [BaseLifecycleComponent#doStart()]. That value must reach the component, so the admitted window opens there.
    @Test
    void ifNotStoppedRunsTheActionDeliveredSynchronouslyDuringStart() {
        component.onStart = () -> component.guardedRun("during-start");

        component.start();

        assertThat(actionsRun).containsExactly("during-start");
    }

    @Test
    void ifNotStoppedSkipsTheActionBeforeStart() {
        component.guardedRun("before-start");

        assertThat(actionsRun).isEmpty();
    }

    @Test
    void ifNotStoppedSkipsTheActionAfterStop() {
        component.start();
        component.stop();

        component.guardedRun("after-stop");

        assertThat(actionsRun).isEmpty();
    }

    /// The shape the guard is written for: the check sits inside the submitted task rather than around the submission, so a stop landing between the two
    /// skips the work. Deciding at submission time would have admitted this task.
    @Test
    void ifNotStoppedSkipsATaskSubmittedBeforeTheStopThatRunsAfterIt() {
        var clock = new ProgrammableClock();
        var executor = clock.createSingleThreadedSchedulingExecutor("test");
        component.start();

        executor.tryExecute("task", () -> {
            actionsRun.add("task-entered");
            component.guardedRun("submitted-before-stop");
        });
        component.stop();
        clock.tick();

        // Recording the entry proves the executor really ran the task, so the absent second entry is the guard's doing.
        assertThat(actionsRun).containsExactly("task-entered");
    }

    /// A callback that arrives while teardown is under way is turned away, because the executor it would submit to is torn down moments later.
    @Test
    void ifNotStoppedSkipsTheActionDuringStop() {
        component.onStop = () -> component.guardedRun("during-stop");
        component.start();

        component.stop();

        assertThat(actionsRun).isEmpty();
    }

    private final class TestComponent extends BaseLifecycleComponent {
        private Runnable onStart = () -> {};
        private Runnable onStop = () -> {};

        void guardedRun(String actionName) {
            ifNotStopped(() -> actionsRun.add(actionName));
        }

        @Override
        protected void doStart() {
            onStart.run();
        }

        @Override
        protected void doStop() {
            onStop.run();
        }
    }
}
